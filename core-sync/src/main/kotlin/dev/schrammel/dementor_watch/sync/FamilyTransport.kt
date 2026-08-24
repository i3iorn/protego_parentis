package dev.schrammel.dementor_watch.sync

import java.time.Instant

/** What a publish or poll attempt did. */
sealed interface TransportResult<out T> {
    data class Ok<T>(val value: T) : TransportResult<T>

    /**
     * [message] describes the transport, never the traffic.
     *
     * It is shown on a settings screen and can end up in a support conversation,
     * so "server returned 503" is welcome here and anything about what was being
     * sent is not.
     */
    data class Failed(val message: String) : TransportResult<Nothing>
}

/** Which backend carries a family's messages. */
enum class TransportKind {
    /** A public ntfy topic, polled. The only one implemented today. */
    NTFY,
    ;

    companion object {
        fun parse(value: String?): TransportKind? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

/**
 * Somewhere a family's messages can be left and collected.
 *
 * Deliberately small, and deliberately ignorant of what it carries: every payload
 * is an already-sealed, already-signed envelope, so a transport can read nothing
 * and is trusted with nothing. That is what makes swapping one for another a
 * contained change rather than a security review — a new backend cannot weaken
 * confidentiality or authenticity, because it never had either to weaken.
 *
 * Two operations, because that is all the protocol needs. A transport that could
 * push would be welcome and would still implement exactly this; the polling loop
 * would simply stop being the only way messages arrive.
 *
 * Every call blocks. Callers must be off the main thread.
 */
interface FamilyTransport {

    /** Which backend this is, for showing and for storing. */
    val kind: TransportKind

    fun publish(envelope: String): TransportResult<Unit>

    /** Everything published since [since]. */
    fun poll(since: Instant): TransportResult<List<String>>
}

/**
 * Everything needed to reach a family, apart from the passphrase.
 *
 * Separated from the passphrase on purpose: this half can be read aloud, texted
 * or put in a QR code, and the half that unlocks it travels another way. A token
 * on its own gets somebody to the right topic and no further — they still cannot
 * read a word of it, and with the roster in place they cannot act either.
 */
data class FamilyToken(
    val kind: TransportKind,
    val serverUrl: String,
    val topic: String,
) {
    init {
        require(serverUrl.startsWith("https://")) { "a family server must be https" }
        require(isValidTopic(topic)) { "not a usable topic name: '$topic'" }
    }

    companion object {
        /**
         * What a topic may contain.
         *
         * The topic is typed by a person and then goes into a URL path and a query
         * string. Checked here rather than at the screen, because this type is
         * what every backend is handed and a value that reaches a URL unchecked is
         * not one to leave unchecked.
         */
        private val TOPIC_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")

        fun isValidTopic(value: String): Boolean = TOPIC_PATTERN.matches(value.trim())
    }
}

/**
 * Turns a [FamilyToken] into something a person can pass to another person.
 *
 * A prefix so a pasted string can be recognised as one of ours, a version so the
 * format can change without older phones guessing, and a checksum because these
 * are read aloud and typed in by hand — and a topic with one character wrong
 * fails by silently reaching an empty topic forever rather than by saying no.
 */
object FamilyTokens {

    private const val PREFIX = "DW1"
    private const val SEPARATOR = '-'
    private const val FIELD_SEPARATOR = '\u001F'
    private const val CHECKSUM_LENGTH = 4

    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    fun encode(token: FamilyToken): String {
        val body = listOf(token.kind.name, token.serverUrl, token.topic)
            .joinToString(FIELD_SEPARATOR.toString())
        val payload = base32(body.toByteArray(Charsets.UTF_8))
        return "$PREFIX$SEPARATOR$payload$SEPARATOR${checksum(payload)}"
    }

    /**
     * Reads a token, or null.
     *
     * Null for everything wrong — not ours, wrong version, mistyped, a backend
     * this build has never heard of. A caller has exactly one sensible response
     * to all of those, which is to say the code was not recognised.
     */
    fun decode(value: String): FamilyToken? {
        val cleaned = value.trim().uppercase().replace(" ", "")
        val parts = cleaned.split(SEPARATOR)
        if (parts.size != 3 || parts[0] != PREFIX) return null

        val payload = parts[1]
        if (parts[2] != checksum(payload)) return null

        val body = unbase32(payload)?.toString(Charsets.UTF_8) ?: return null
        val fields = body.split(FIELD_SEPARATOR)
        if (fields.size != 3) return null

        val kind = TransportKind.parse(fields[0]) ?: return null
        return runCatching { FamilyToken(kind, fields[1], fields[2]) }.getOrNull()
    }

    /**
     * Crockford's alphabet, so a code survives being read out.
     *
     * No I, L, O or U: nothing to confuse with 1 or 0, and no accidental words.
     * The server URL is mixed case and the topic may be, so the bytes are encoded
     * rather than the text — an uppercased code decodes back to the original.
     */
    private fun base32(bytes: ByteArray): String {
        val out = StringBuilder()
        var buffer = 0L
        var bits = 0
        bytes.forEach { byte ->
            buffer = (buffer shl 8) or (byte.toLong() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out.append(ALPHABET[((buffer shr bits) and 0x1F).toInt()])
            }
        }
        if (bits > 0) out.append(ALPHABET[((buffer shl (5 - bits)) and 0x1F).toInt()])
        return out.toString()
    }

    private fun unbase32(text: String): ByteArray? {
        val out = ArrayList<Byte>(text.length)
        var buffer = 0L
        var bits = 0
        text.forEach { character ->
            val index = ALPHABET.indexOf(character)
            if (index < 0) return null
            buffer = (buffer shl 5) or index.toLong()
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.add(((buffer shr bits) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }

    private fun checksum(payload: String): String {
        // Not a security property — a typo detector. The signature and the
        // passphrase are what stop anyone acting on a topic they guessed.
        var hash = 0
        payload.forEach { character -> hash = hash * 31 + character.code }
        return buildString {
            repeat(CHECKSUM_LENGTH) { index ->
                append(ALPHABET[((hash shr (index * 5)) and 0x1F)])
            }
        }
    }
}
