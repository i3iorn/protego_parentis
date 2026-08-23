package dev.schrammel.dementor_watch.model

import java.security.MessageDigest

/** What a walk of the chain found. */
sealed interface ChainCheck {

    /** Every entry follows from the one before it. */
    data object Intact : ChainCheck

    /**
     * The chain breaks at [index].
     *
     * The index is where the discrepancy becomes visible, which is at or after the
     * edit: changing entry five invalidates five and every link after it. So this
     * says "something was changed no later than here", not "entry five is the
     * forgery" — and the screen says it that way too.
     */
    data class Broken(val index: Int) : ChainCheck

    val isIntact: Boolean get() = this is Intact
}

/**
 * Links each history entry to the one before it (TR-3).
 *
 * The shared history's whole value is that both sides believe it. A plain list in
 * a preferences file can be edited by anyone who can reach the file — on a rooted
 * phone, that is the person most motivated to edit it — and nothing would show.
 * Chaining means an edit anywhere invalidates every link after it, so the app can
 * say plainly that the record no longer adds up.
 *
 * What this does **not** do: stop the app itself rewriting the log. Anyone who can
 * run this code can recompute a consistent chain. It detects tampering from
 * outside the app — a text editor, a restored backup, a corrupted write — which is
 * the realistic threat. Detecting the other kind needs a key the device cannot
 * read, and there is nowhere on a child's phone to keep one.
 */
object AuditChain {

    /** Where a chain starts before anything has been written. */
    const val GENESIS = "0"

    /**
     * ASCII unit separator, joining the fields before they are hashed.
     *
     * A character no field can contain, so "ab" then "c" cannot hash the same
     * as "a" then "bc" — which is how a naive concatenation lets two different
     * histories share a link.
     */
    private const val SEPARATOR = '\u001F'

    /**
     * The link for [entry] following [previousHash].
     *
     * Every field is covered. Leaving one out — the timestamp, say — would allow
     * exactly the edit most worth making: moving a rule change to a different
     * evening while the chain still verifies.
     */
    fun link(entry: AuditEntry, previousHash: String): String {
        val canonical = listOf(
            previousHash,
            entry.id,
            entry.at.toEpochMilli().toString(),
            entry.actor.name,
            entry.action.name,
            entry.subject.orEmpty(),
            entry.before.orEmpty(),
            entry.after.orEmpty(),
        ).joinToString(SEPARATOR.toString())

        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    /**
     * Walks [entries] from [anchor], oldest first.
     *
     * [anchor] is the hash the first entry should follow. It is not always
     * [GENESIS]: the log is capped, so once old entries age out the chain
     * continues from the hash of the last one dropped. Keeping that anchor is what
     * stops trimming looking identical to deletion.
     */
    fun verify(entries: List<AuditEntry>, anchor: String = GENESIS): ChainCheck {
        var previous = anchor
        entries.forEachIndexed { index, entry ->
            if (entry.previousHash != previous) return ChainCheck.Broken(index)
            val expected = link(entry, previous)
            if (entry.hash != expected) return ChainCheck.Broken(index)
            previous = expected
        }
        return ChainCheck.Intact
    }
}
