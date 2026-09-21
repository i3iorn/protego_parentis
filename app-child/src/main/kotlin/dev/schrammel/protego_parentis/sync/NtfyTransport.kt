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
import dev.schrammel.protego_parentis.sync.core.MessageCrypto

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

    override val maxEnvelopeChars: Int = MAX_ENVELOPE_CHARS

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
     *
     * Reads through [readBoundedLine] rather than [BufferedReader.readLine],
     * which has no length limit of its own — it keeps growing its buffer
     * until it finds a line terminator or the stream ends, so the previous
     * "drop anything over [MAX_LINE_CHARS]" filter only ever ran *after* an
     * oversized line was already fully in memory (#252). A line with no
     * terminator at all was bounded only by [TIMEOUT_MILLIS] and whatever
     * fit in that time, not by [MAX_LINE_CHARS]. [readBoundedLine] keeps the
     * buffer itself bounded regardless of how long the line turns out to be.
     *
     * Internal rather than private so a test can drive it directly with a
     * synthetic long line, without standing up a fake server for something
     * that is otherwise pure string handling.
     */
    internal fun readEnvelopes(reader: BufferedReader): List<String> {
        val messages = mutableListOf<String>()
        while (messages.size < MAX_MESSAGES_PER_POLL) {
            val line = when (val result = readBoundedLine(reader, MAX_LINE_CHARS)) {
                LineResult.EndOfStream -> break
                // Dropped without being parsed, same as before — just never
                // held in full to begin with. Reading continues at the next
                // line rather than aborting the whole poll, so one bad line
                // (from a stranger's traffic on the same public topic) does
                // not cost every legitimate message still queued behind it.
                LineResult.TooLong -> continue
                is LineResult.Ok -> result.line
            }
            if (line.isBlank()) continue
            val json = runCatching { JSONObject(line) }.getOrNull() ?: continue
            if (json.optString(FIELD_EVENT) != EVENT_MESSAGE) continue
            val message = json.optString(FIELD_MESSAGE).takeIf(String::isNotBlank) ?: continue
            messages += message
        }
        return messages
    }

    /** What [readBoundedLine] found. */
    private sealed interface LineResult {
        data class Ok(val line: String) : LineResult
        /** A line existed but passed [maxChars] before its terminator. */
        data object TooLong : LineResult
        data object EndOfStream : LineResult
    }

    /**
     * Reads one line, holding at most [maxChars] characters in memory no
     * matter how long the actual line turns out to be — see [readEnvelopes]
     * for why that matters. Once the cap is passed, further characters up to
     * the next `\n` (or the stream ending) are read one at a time and
     * discarded rather than appended, so the line boundary is still found
     * correctly and reading can resume at the next line.
     */
    private fun readBoundedLine(reader: BufferedReader, maxChars: Int): LineResult {
        val builder = StringBuilder()
        var oversized = false
        while (true) {
            val next = reader.read()
            if (next < 0) {
                return when {
                    oversized -> LineResult.TooLong
                    builder.isEmpty() -> LineResult.EndOfStream
                    else -> LineResult.Ok(builder.toString())
                }
            }
            if (next == '\n'.code) {
                // A trailing \r (the common HTTP line ending) is not part of
                // the content.
                if (builder.isNotEmpty() && builder.last() == '\r') builder.setLength(builder.length - 1)
                return if (oversized) LineResult.TooLong else LineResult.Ok(builder.toString())
            }
            if (builder.length < maxChars) builder.append(next.toChar()) else oversized = true
        }
    }

    private fun open(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
            // No redirects: a redirect off our configured host would send traffic
            // somewhere we did not choose.
            instanceFollowRedirects = false
        }

    // Internal rather than private so a test can reference MAX_LINE_CHARS
    // directly instead of duplicating the number.
    internal companion object {
        const val TAG = "DementorSync"
        val TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(15).toInt()

        /** A bound, so a flooded topic cannot exhaust memory on the child's phone. */
        const val MAX_MESSAGES_PER_POLL = 200

        /**
         * The longest line worth reading.
         *
         * The real ceiling is a full app inventory (#85): every launchable app on
         * the phone, package name and label, one JSON object per app, nested
         * inside a signed [dev.schrammel.protego_parentis.sync.core.SyncMessage]
         * and then AES-GCM-sealed and base64'd — two layers of re-encoding on
         * top of the raw list. A phone with a couple hundred apps was clearing
         * the previous 16,384-character bound and having its reply silently
         * dropped right here, with nothing in any log to say so: the picker's
         * "request list" button looked wired up, the child's own Diagnostics
         * showed a normal successful publish, and the list simply never arrived
         * on the parent's side. 256 KiB comfortably covers a phone with several
         * thousand installed apps and is still far below anything that would
         * trouble the heap.
         */
        const val MAX_LINE_CHARS = 262_144

        /**
         * The budget [dev.schrammel.protego_parentis.sync.core.MessageFragments]
         * is told to keep every published wire message under.
         *
         * ntfy's own default `message-size-limit` is 4,096 bytes; a message
         * over that is not rejected but silently turned into a downloadable
         * attachment instead, which nothing on the reading side of this app
         * ever fetches — the real reason #85 kept resurfacing even after
         * [MAX_LINE_CHARS] was raised, since that only ever guarded how much
         * of an already-delivered message this app was willing to read, not
         * whether the relay delivered it as a message at all. Kept well under
         * 4,096 rather than flush against it: the family's own relay may not
         * be the public server, ntfy's docs call anything near that ceiling
         * "largely untested", and there is still the fragment header itself
         * to leave room for.
         */
        const val MAX_ENVELOPE_CHARS = 3_800

        const val FIELD_EVENT = "event"
        const val FIELD_MESSAGE = "message"
        const val EVENT_MESSAGE = "message"
    }
}
