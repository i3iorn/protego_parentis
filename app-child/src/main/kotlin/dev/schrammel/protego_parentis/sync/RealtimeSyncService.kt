package dev.schrammel.protego_parentis.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.diagnostics.DiagnosticEvent
import dev.schrammel.protego_parentis.diagnostics.Diagnostics
import dev.schrammel.protego_parentis.sync.core.TransportKind
import dev.schrammel.protego_parentis.ui.HomeActivity
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Keeps one long-lived connection to the family topic open, so a request, a
 * decision or a lock command lands the moment it is published rather than on
 * the next poll (see [ParentPollWorker], which stays as the reconciliation
 * safety net for when this is down).
 *
 * ntfy's `/sse` endpoint is a plain HTTP response that never closes — this reads
 * it a line at a time on a dedicated thread and hands anything that looks like a
 * message straight to [SyncCoordinator.receiveEnvelope]. [NtfyTransport] stays
 * poll-only on purpose (see its own doc comment on why a held-open connection is
 * usually the wrong trade); this service is that trade made deliberately, in
 * exchange for a foreground notification a person can see and end.
 *
 * [start] and [stop] are both idempotent from any thread: starting an
 * already-running service, or stopping one that never started, does nothing
 * harmful.
 */
class RealtimeSyncService : Service() {

    private lateinit var coordinator: SyncCoordinator
    private lateinit var config: SyncConfigStore
    private lateinit var executor: ExecutorService

    @Volatile private var stopped = false

    @Volatile private var currentConnection: HttpURLConnection? = null

    /**
     * Guards against submitting a second stream loop to [executor].
     *
     * Android creates one [Service] instance per running service and calls
     * [onCreate] once, but [onStartCommand] can run again on that same instance
     * (a second [start] call, a config-changed restart) while the first loop is
     * still connected — without this that would open a second socket.
     */
    private var streamStarted = false

    override fun onCreate() {
        super.onCreate()
        coordinator = SyncCoordinator(this)
        config = SyncConfigStore(this)
        executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "dementor-realtime-sync").apply { isDaemon = true }
        }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must run unconditionally and first: the OS requires a service started
        // via startForegroundService() to call this within seconds, regardless
        // of what onStartCommand decides next, or it kills the app for the
        // broken promise.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        if (!config.load().isUsable) {
            // Nothing to connect to yet; a half-configured phone does nothing
            // rather than spinning on a connection it cannot open.
            stopSelf()
            return START_NOT_STICKY
        }

        if (!streamStarted) {
            streamStarted = true
            executor.execute(::streamLoop)
        }

        // START_NOT_STICKY: reconnecting after process death is [BootReceiver]'s
        // and every screen's job (they call start() on their own triggers), not
        // something worth restarting for on a bare kill with no one asking.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopped = true
        currentConnection?.disconnect()
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Reconnects with a growing backoff for as long as the family stays
     * configured for ntfy. Exits (and stops the service) the moment it is not —
     * someone left the family, or switched transports — rather than retrying
     * against settings that no longer apply.
     */
    private fun streamLoop() {
        var backoffMillis = MIN_BACKOFF_MILLIS
        while (!stopped) {
            val settings = config.load()
            if (!settings.isUsable || settings.transport != TransportKind.NTFY) {
                stopSelf()
                return
            }

            val connected = runCatching { openStream(settings) }
            if (connected.isSuccess) {
                // A clean read loop (the connection was closed or timed out)
                // is not the same failure as never connecting at all; reset
                // the backoff so a brief blip does not linger as a slow
                // reconnect.
                backoffMillis = MIN_BACKOFF_MILLIS
            } else {
                Log.d(TAG, "Realtime stream failed", connected.exceptionOrNull())
                Diagnostics.record(this, DiagnosticEvent.SYNC_STREAM_FAILED)
            }

            if (stopped) return
            try {
                Thread.sleep(backoffMillis)
            } catch (_: InterruptedException) {
                return
            }
            backoffMillis = (backoffMillis * 2).coerceAtMost(MAX_BACKOFF_MILLIS)
        }
    }

    /** Opens the stream and reads from it until it ends, fails, or [stopped]. */
    private fun openStream(settings: SyncSettings) {
        val connection = (URL("${settings.serverUrl}/${settings.topic}/sse").openConnection()
            as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            // Comfortably longer than ntfy's own keepalive interval, so a
            // silently dead connection is noticed rather than blocking forever.
            readTimeout = READ_TIMEOUT_MILLIS
            instanceFollowRedirects = false
        }
        currentConnection = connection
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                error("server returned $code")
            }
            connection.inputStream.bufferedReader().use { reader ->
                while (!stopped) {
                    val line = reader.readLine() ?: break
                    val envelope = RealtimeSseEnvelope.parse(line) ?: continue
                    deliver(envelope)
                }
            }
        } finally {
            connection.disconnect()
            currentConnection = null
        }
    }

    /** Decrypts and applies one envelope, on the shared sync executor. */
    private fun deliver(envelope: String) {
        val settings = config.load()
        val key = SyncKeying.keyFor(settings) ?: return
        SyncKeying.execute {
            coordinator.receiveEnvelope(key, envelope)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.realtime_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.realtime_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(getString(R.string.realtime_notification_title))
        .setContentText(getString(R.string.realtime_notification_text))
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, HomeActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    companion object {
        private const val TAG = "DementorRealtimeSync"
        private const val CHANNEL_ID = "realtime_sync"
        private const val NOTIFICATION_ID = 6

        private const val CONNECT_TIMEOUT_MILLIS = 15_000
        private val READ_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(90).toInt()

        private const val MIN_BACKOFF_MILLIS = 2_000L
        private const val MAX_BACKOFF_MILLIS = 5 * 60_000L

        /** Starts the stream, unless the family is not configured to have one. */
        fun start(context: Context) {
            val settings = SyncConfigStore(context).load()
            if (!settings.isUsable) return
            context.startForegroundService(Intent(context, RealtimeSyncService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RealtimeSyncService::class.java))
        }
    }
}
