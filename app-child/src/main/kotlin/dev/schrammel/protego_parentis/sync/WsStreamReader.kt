package dev.schrammel.protego_parentis.sync

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Holds one long-lived streaming read from an ntfy topic.
 *
 * ntfy's `/json` endpoint streams newline-delimited JSON — one object per line —
 * and keeps the connection open rather than returning and closing as the `poll=1`
 * variant does. This is the "realtime push" half of the transport: instead of
 * asking "anything since X?" every so often, the app keeps a connection open and
 * is told the moment a message lands. Same wire format, same envelopes, same
 * security boundary as [NtfyTransport] — the server still sees only sealed
 * ciphertext.
 *
 * Deliberately the only piece of the sync layer that holds a connection open. It
 * runs on its own dedicated thread (see [SyncSocketManager]) so it never blocks
 * the shared [SyncKeying] executor, and it hands each envelope to the rest of the
 * pipeline for the state-mutating part. Any network error ends the stream; the
 * caller is responsible for deciding whether to reconnect and when.
 *
 * Every call here blocks; run it on a background thread.
 */
class WsStreamReader(
    private val serverUrl: String,
    private val topic: String,
    /** Called with each envelope (a `"message"` body) as it arrives. */
    private val onEnvelope: (String) -> Unit,
) {
    /**
     * Reads until the connection drops, the stream is [closed], or the topic is
     * cleared. Returns a short reason so the caller can log and schedule a
     * reconnect.
     */
    fun readLoop(sinceSeconds: Long): EndReason {
        val connection = open(sinceSeconds) ?: return EndReason.UNABLE_TO_OPEN
        return try {
            val reader = connection.inputStream.bufferedReader()
            try {
                readForever(reader)
            } finally {
                runCatching { reader.close() }
            }
        } catch (e: Exception) {
            if (closed) EndReason.CLOSED else EndReason.from(e)
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    /**
     * Signals an in-progress [readLoop] to stop. The read is blocked on the
     * socket, so closing the connection forces the blocked read to throw and end.
     */
    fun close() {
        closed = true
        runCatching { openConnection?.disconnect() }
    }

    private fun open(sinceSeconds: Long): HttpURLConnection? {
        val url = "$serverUrl/$topic/json?since=${sinceSeconds.coerceAtLeast(0)}"
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                // No read timeout: the whole point is a long-lived read. A server
                // that stops sending keepalives is caught by the caller's watchdog.
                readTimeout = 0
                instanceFollowRedirects = false
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.d(TAG, "Stream open failed: HTTP $code")
                conn.disconnect()
                openConnection = null
                null
            } else {
                openConnection = conn
                conn
            }
        } catch (e: IOException) {
            Log.d(TAG, "Stream open failed", e)
            null
        }
    }

    private fun readForever(reader: BufferedReader): EndReason {
        while (true) {
            if (closed) return EndReason.CLOSED
            val line = reader.readLine() ?: return EndReason.END_OF_STREAM
            envelopeFrom(line)?.let(onEnvelope)
        }
    }

    /**
     * Pulls the envelope body out of one streamed line, or null when the line is
     * not a message we recognise.
     *
     * The topic is public, so — exactly as with a poll — most of it is other
     * people's traffic: open/keepalive events, our own messages, malformed noise.
     * Only a `"message"` event with a non-blank, plausibly-sized body is worth
     * handing on. Split out from the read loop so it can be unit-tested without
     * a live socket.
     */
    internal fun envelopeFrom(line: String): String? {
        if (line.isNullOrBlank() || line.length > MAX_LINE_CHARS) return null
        val json = runCatching { JSONObject(line) }.getOrNull() ?: return null
        if (json.optString(FIELD_EVENT) != EVENT_MESSAGE) return null
        return json.optString(FIELD_MESSAGE).takeIf(String::isNotBlank)
    }

    enum class EndReason {
        /** Connection opened but no stream established. */
        UNABLE_TO_OPEN,

        /** The server closed the stream cleanly (e.g. topic cleared). */
        END_OF_STREAM,

        /** [close] was called. */
        CLOSED,

        /** Network/transport failure; worth retrying. */
        NETWORK_ERROR,

        /** Anything else; treat like a retryable network failure. */
        OTHER;

        companion object {
            fun from(e: Exception): EndReason = when (e) {
                is SocketTimeoutException, is UnknownHostException,
                is java.net.ConnectException, is IOException -> NETWORK_ERROR
                else -> OTHER
            }
        }
    }

    private companion object {
        const val TAG = "DementorSync"
        val CONNECT_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(15).toInt()
        const val MAX_LINE_CHARS = 16_384
        const val FIELD_EVENT = "event"
        const val FIELD_MESSAGE = "message"
        const val EVENT_MESSAGE = "message"
    }

    // Guarded by the single reading thread; read/closed visibility via @Volatile.
    @Volatile private var closed = false
    @Volatile private var openConnection: HttpURLConnection? = null
}
