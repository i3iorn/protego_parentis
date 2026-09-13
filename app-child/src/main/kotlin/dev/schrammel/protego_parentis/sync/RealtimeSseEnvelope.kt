package dev.schrammel.protego_parentis.sync

import org.json.JSONObject

/**
 * Reads one envelope out of one line of an ntfy `/sse` stream.
 *
 * ntfy frames every event as a `data: {...}` line (plus blank separator lines and
 * `event:`/`id:` lines this app has no use for), where the JSON body is the same
 * shape [NtfyTransport] already parses out of poll mode — an `event` field and,
 * for a real message, a `message` field carrying the sealed envelope. Split out
 * of [RealtimeSyncService] so the parsing can be tested without a socket or a
 * `Service`.
 */
object RealtimeSseEnvelope {

    private const val DATA_PREFIX = "data:"
    private const val FIELD_EVENT = "event"
    private const val FIELD_MESSAGE = "message"
    private const val EVENT_MESSAGE = "message"

    /**
     * A bound, so one oversized line cannot be pulled into memory in full.
     *
     * Kept in step with [NtfyTransport.MAX_LINE_CHARS] — see that constant's
     * doc comment for why 16,384 was too small: a full app inventory (#85)
     * clears it on any phone with a realistic number of installed apps, and
     * this is the near-instant delivery path most replies actually arrive
     * through, so the same silent drop happened here too.
     */
    internal const val MAX_LINE_CHARS = 262_144

    /** Returns the envelope in [line], or null if the line carries none. */
    fun parse(line: String): String? {
        if (!line.startsWith(DATA_PREFIX) || line.length > MAX_LINE_CHARS) return null
        val json = runCatching { JSONObject(line.removePrefix(DATA_PREFIX).trim()) }.getOrNull()
            ?: return null
        if (json.optString(FIELD_EVENT) != EVENT_MESSAGE) return null
        return json.optString(FIELD_MESSAGE).takeIf(String::isNotBlank)
    }
}
