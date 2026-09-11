package dev.schrammel.protego_parentis.sync

import android.util.Log
import dev.schrammel.protego_parentis.sync.core.FamilyTransport
import dev.schrammel.protego_parentis.sync.core.TransportFailureKind
import dev.schrammel.protego_parentis.sync.core.TransportKind
import dev.schrammel.protego_parentis.sync.core.TransportResult
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.time.Instant
import java.util.concurrent.TimeUnit
import dev.schrammel.protego_parentis.sync.core.FamilyTransport
import dev.schrammel.protego_parentis.sync.core.MessageCrypto
import dev.schrammel.protego_parentis.sync.core.TransportFailureKind
import dev.schrammel.protego_parentis.sync.core.TransportKind
import dev.schrammel.protego_parentis.sync.core.TransportResult

/**
 * Carries a family's messages over an ntfy topic.
 *
 * Deliberately thin, and deliberately unaware of what it is carrying: bodies are
 * already-sealed envelopes from `MessageCrypto`. The server is treated as a dumb
 * relay that can read nothing and is trusted with nothing.
 *
 * Polling rather than a streaming subscription. A held-open connection on a
 * child's phone is a battery and reconnection problem, and the app already wakes
 * on a schedule for enforcement — piggybacking on that is far simpler than
 * managing a socket across doze, network changes and process death.
 *
 * Every call blocks; callers must be off the main thread.
 */
class NtfyTransport(
    private val serverUrl: String,
    private val topic: String,
) : FamilyTransport {

    override val kind: TransportKind = TransportKind.NTFY

    /** Publishes one already-encrypted envelope. */
    override fun publish(envelope: String): TransportResult<Unit> = try {
        val connection = open("$serverUrl/$topic", method = "POST")
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "text/plain")
        // ntfy would otherwise try to render our ciphertext as a notification
        // title on any device that happens to be subscribed with its own app.
        connection.setRequestProperty("X-Title", "Dementor Watch")
        connection.outputStream.use { it.write(envelope.toByteArray(Charsets.UTF_8)) }

        val code = connection.responseCode
        connection.disconnect()

        if (code in 200..299) {
            TransportResult.Ok(Unit)
        } else {
            TransportResult.Failed("server returned $code", TransportFailureKind.HTTP_ERROR, code)
        }
    } catch (e: Exception) {
        // Offline is the normal case, not an error worth shouting about.
        Log.d(TAG, "Publish failed", e)
        TransportResult.Failed(e.message ?: "could not reach the server", classify(e))
    }

    /**
     * Fetches envelopes published since [since].
     *
     * Uses ntfy's poll mode, which returns everything available and closes rather
     * than streaming. Anything that is not a message body we recognise is skipped
     * — the topic is public and will contain other traffic.
     */
    override fun poll(since: Instant): TransportResult<List<String>> = try {
        val seconds = since.epochSecond.coerceAtLeast(0)
        val connection = open("$serverUrl/$topic/json?poll=1&since=$seconds", method = "GET")

        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            TransportResult.Failed("server returned $code", TransportFailureKind.HTTP_ERROR, code)
        } else {
            val envelopes = connection.inputStream.bufferedReader().use(::readEnvelopes)
            connection.disconnect()
            TransportResult.Ok(envelopes)
        }
    } catch (e: Exception) {
        Log.d(TAG, "Poll failed", e)
        TransportResult.Failed(e.message ?: "could not reach the server", classify(e))
    }

    /** Sorts a caught exception into one of the handful of kinds worth telling apart. */
    private fun classify(e: Exception): TransportFailureKind = when (e) {
        is SocketTimeoutException -> TransportFailureKind.TIMEOUT
        is UnknownHostException -> TransportFailureKind.UNREACHABLE
        else -> TransportFailureKind.OTHER
    }

    /**
     * ntfy streams newline-delimited JSON; one object per line.
     *
     * Lines longer than a family message could plausibly be are dropped without
     * being parsed. The topic is public and anyone who learns its name can publish
     * to it — they can read nothing, but a single enormous line would otherwise be
     * pulled into memory in full before anything looked at its size.
     */
    private fun readEnvelopes(reader: BufferedReader): List<String> =
        reader.lineSequence()
            .filter { it.isNotBlank() && it.length <= MAX_LINE_CHARS }
            .mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
            .filter { it.optString(FIELD_EVENT) == EVENT_MESSAGE }
            .mapNotNull { it.optString(FIELD_MESSAGE).takeIf(String::isNotBlank) }
            .take(MAX_MESSAGES_PER_POLL)
            .toList()

    private fun open(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
            // No redirects: a redirect off our configured host would send traffic
            // somewhere we did not choose.
            instanceFollowRedirects = false
        }

    private companion object {
        const val TAG = "DementorSync"
        val TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(15).toInt()

        /** A bound, so a flooded topic cannot exhaust memory on the child's phone. */
        const val MAX_MESSAGES_PER_POLL = 200

        /**
         * The longest line worth reading.
         *
         * Comfortably above a real message — the largest this app sends is a
         * daily summary with a handful of app names — and far below anything that
         * would trouble the heap.
         */
        const val MAX_LINE_CHARS = 16_384

        const val FIELD_EVENT = "event"
        const val FIELD_MESSAGE = "message"
        const val EVENT_MESSAGE = "message"
    }
}
