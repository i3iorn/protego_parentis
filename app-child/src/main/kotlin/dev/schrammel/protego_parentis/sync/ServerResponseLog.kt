package dev.schrammel.protego_parentis.sync

import java.time.Instant

/**
 * The last response the relay gave this process, held for the backend screen.
 *
 * A refused sync looks identical from the outside to a broken one: the app says
 * "server returned 403" and the relay's log says nothing, because the request
 * never reached ntfy (a reverse proxy answered instead). The difference between
 * those two is visible only in the response itself — which proxy answered, what
 * it said, which headers came back — and the only place a person can read that
 * is the screen where they just typed the credentials.
 *
 * Kept process-wide rather than persisted: this is debugging help for the
 * visit in which it was produced, not a record. Bodies are bounded so a
 * chatty proxy cannot balloon memory. Authorization headers are never stored —
 * a response snapshot must not become a credential leak.
 */
object LastServerResponse {

    data class Snapshot(
        val at: Instant,
        /** The request line, without any credential: method, host, path. */
        val request: String,
        val status: Int,
        /** Response headers, minus any credential-bearing one. */
        val headers: String,
        /** The body, truncated to [MAX_BODY_CHARS]. Empty for 2xx. */
        val body: String,
        /** What the response was in answer to: "publish", "poll" or "test". */
        val phase: String,
    )

    @Volatile
    private var current: Snapshot? = null

    fun record(snapshot: Snapshot) {
        current = snapshot
    }

    fun peek(): Snapshot? = current

    internal fun clearForTest() {
        current = null
    }

    const val MAX_BODY_CHARS = 4_096

    /** Headers worth keeping, with anything a credential could hide in dropped. */
    fun formatHeaders(headers: Map<String, List<String>>): String = headers.entries
        .filter { !it.key.equals("Authorization", ignoreCase = true) }
        .joinToString("\n") { (name, values) ->
            values.joinToString("\n") { value -> "$name: $value" }
        }

    fun body(text: String): String =
        if (text.length <= MAX_BODY_CHARS) text else text.take(MAX_BODY_CHARS) + "…"
}
