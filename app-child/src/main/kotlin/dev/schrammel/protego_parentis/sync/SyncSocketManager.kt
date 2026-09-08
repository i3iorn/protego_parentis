package dev.schrammel.protego_parentis.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import dev.schrammel.protego_parentis.diagnostics.DiagnosticEvent
import dev.schrammel.protego_parentis.diagnostics.Diagnostics
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns the single realtime stream a device keeps open to the family topic.
 *
 * Previously every device learned about new traffic only by asking, on a poll —
 * fifteen minutes apart on a parent's phone, which is how a two-hour request
 * expiry became a coin flip. This keeps a long-lived `/json` stream open instead,
 * so a message is applied the moment it arrives on either side of the family.
 *
 * The stream's read loop runs on its **own** dedicated thread: it blocks on the
 * socket for as long as the connection is alive, and must not occupy the shared
 * [SyncKeying] executor that publish and poll need. Each envelope that arrives is
 * handed to the shared executor for the state-mutating part ([SyncReceiver]
 * preserves the single-threaded replay-guard invariant). Only one stream is ever
 * open at a time; ensure/close are idempotent and safe to call from anywhere.
 *
 * When the stream drops it is reopened with a bounded backoff, and a
 * connectivity callback reopens it immediately on a network change rather than
 * waiting out the backoff. Messages missed while the connection was down are
 * fetched on the next poll — the existing reconciliation pass — so streaming and
 * polling are complementary, not competing, ways to converge.
 */
internal class SyncSocketManager(context: Context) {

    private val appContext = context.applicationContext
    private val config = SyncConfigStore(appContext)
    private val receiver = SyncReceiver(appContext, SyncPublisher(appContext))

    private val io: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dementor-stream").apply { isDaemon = true }
    }

    private val running = AtomicBoolean(false)
    private val connectionAttempts = AtomicInteger(0)

    private val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val connectCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // A network just appeared (or returned from doze); no point
            // waiting out a backoff when we now know the way out works.
            if (running.get()) {
                io.execute { reopen("network available") }
            }
        }
    }

    /** Opens (or reopens) the stream if the family is usable. Safe to call repeatedly. */
    fun ensureConnected() {
        if (!running.compareAndSet(false, true)) {
            // Already managing a connection; a fresh one may be wanted after a
            // settings change, but this is the idempotent entry point.
            return
        }
        runCatching { cm.registerDefaultNetworkCallback(connectCallback) }
        io.execute { openLoop() }
    }

    /** Stops the stream and forgets it. Safe to call repeatedly and from any thread. */
    fun close() {
        if (!running.compareAndSet(true, false)) return
        // Disconnect directly (not via the io executor) to unblock a readLoop
        // that may be blocked on the socket. The volatile reader reference and
        // WsStreamReader.close() are both safe to touch from any thread.
        activeReader?.close()
        activeReader = null
        connectionAttempts.set(0)
        runCatching { cm.unregisterNetworkCallback(connectCallback) }
    }

    private fun openLoop() {
        while (running.get()) {
            val settings = config.load()
            if (!settings.isUsable) {
                // Leaving the family or half-configured: nothing to stream. Wait a
                // tick for a settings re-read rather than busy-looping.
                sleepQuietly(POLL_RETRY_MILLIS)
                continue
            }

            val since = config.lastPolledAt().epochSecond
            val reader = WsStreamReader(settings.serverUrl, settings.topic) { envelope ->
                dispatch(settings, envelope)
            }
            activeReader = reader
            connectionAttempts.set(0)
            Diagnostics.record(appContext, DiagnosticEvent.SYNC_POLLED, mapOf("stream" to 1L))

            val reason = reader.readLoop(since)
            if (activeReader === reader) activeReader = null

            if (!running.get()) { closeReader(reader); return }

            // CLOSED means the stream was asked to end, not that it failed: the
            // connectivity callback reopened it because a network just became
            // available, in which case the right move is to open afresh right now
            // rather than wait out a backoff. Anything else is a real drop and
            // gets the bounded backoff before the next attempt.
            if (reason == WsStreamReader.EndReason.CLOSED) {
                closeReader(reader)
                continue
            }

            val attempt = connectionAttempts.incrementAndGet()
            Diagnostics.record(
                appContext,
                DiagnosticEvent.SYNC_POLL_FAILED,
                mapOf(
                    "reason" to reason.ordinal.toLong(),
                    "attempt" to attempt.toLong(),
                ),
            )

            Log.d(TAG, "Stream ended ($reason); reconnecting in ${backoffMillis(attempt)}ms")
            closeReader(reader)
            sleepQuietly(backoffMillis(attempt))
        }
    }

    private fun reopen(reason: String) {
        Log.d(TAG, "Reopening stream: $reason")
        activeReader?.close()
    }

    private fun dispatch(settings: SyncSettings, envelope: String) {
        // All state-mutating work — replay guard, roster, apply — must run on the
        // single sync executor so it cannot interleave with a publish or poll.
        SyncKeying.execute {
            val key = SyncKeying.keyFor(settings) ?: return@execute
            receiver.receiveEnvelope(key, envelope)
        }
    }

    private fun closeReader(reader: WsStreamReader) {
        if (activeReader === reader) activeReader = null
        runCatching { reader.close() }
    }

    private fun sleepQuietly(ms: Long) {
        if (ms <= 0) return
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun backoffMillis(attempt: Int): Long {
        var base = BACKOFF_BASE_MILLIS
        repeat(attempt.coerceAtMost(MAX_BACKOFF_STEPS)) { base = base * 2 }
        return base.coerceAtMost(BACKOFF_CAP_MILLIS)
    }

    internal fun shutdown() {
        close()
        io.shutdown()
    }

    private companion object {
        const val TAG = "DementorSync"
        const val POLL_RETRY_MILLIS = 5_000L
        const val BACKOFF_BASE_MILLIS = 5_000L
        const val BACKOFF_CAP_MILLIS = 120_000L
        const val MAX_BACKOFF_STEPS = 5
    }

    @Volatile private var activeReader: WsStreamReader? = null
}
