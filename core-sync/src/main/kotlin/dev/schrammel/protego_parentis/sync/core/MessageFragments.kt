package dev.schrammel.protego_parentis.sync.core

import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Splits a sealed envelope too big for one wire message into several, and puts
 * them back together on the other side.
 *
 * Exists because a relay is free to refuse or mangle a message past its own
 * size limit — a real, measured one for ntfy (its default `message-size-limit`
 * is 4,096 bytes; anything larger is silently turned into a download attachment
 * instead of being delivered as a message, which this app never fetches) — and
 * that limit lives on the server, not in anything this codebase controls. A
 * full app inventory (#85) or a large policy snapshot clears it easily. Cutting
 * the envelope apart before it reaches [FamilyTransport.publish] keeps every
 * individual wire message inside whatever budget the transport reports,
 * regardless of which backend is configured — the same reason [FamilyTransport]
 * itself stays ignorant of what it carries.
 *
 * Operates purely on the already-sealed, opaque envelope string. Signing and
 * encryption happen once, over the whole message, exactly as before; splitting
 * and reassembly never see plaintext.
 */
object MessageFragments {

    private const val PREFIX = "F1:"
    private const val SEPARATOR = ':'

    /**
     * Room reserved for [PREFIX], the two numbers and their separators, on top
     * of the group id. Generous on purpose: undercounting here would let a
     * chunk creep past the caller's budget by a handful of characters.
     */
    private const val HEADER_BUDGET = 32

    /** One piece of a split envelope. */
    data class Fragment(val groupId: String, val index: Int, val total: Int, val chunk: String)

    /**
     * Splits [envelope] into wire strings no longer than [maxWireChars] each.
     *
     * Returns `listOf(envelope)` unchanged when it already fits — the ordinary
     * case for every message type except a handful of large ones, and byte for
     * byte what a build without this feature would have sent, so an older phone
     * reading the same topic still understands it exactly as before.
     *
     * [groupId] identifies the pieces as belonging together. [SyncPublisher]
     * passes the message's own id: already unique per publish, so this adds no
     * new id space to reason about.
     *
     * A base64 envelope can never itself start with [PREFIX] — `:` is not in
     * the base64 alphabet — so an unfragmented envelope and a fragment's wire
     * form can never be mistaken for one another.
     */
    fun split(groupId: String, envelope: String, maxWireChars: Int): List<String> {
        if (envelope.length <= maxWireChars) return listOf(envelope)

        val chunkChars = (maxWireChars - HEADER_BUDGET - groupId.length).coerceAtLeast(1)
        val pieces = envelope.chunked(chunkChars)
        return pieces.mapIndexed { index, chunk ->
            "$PREFIX$groupId$SEPARATOR$index$SEPARATOR${pieces.size}$SEPARATOR$chunk"
        }
    }

    /**
     * Reads one fragment's wire form, or null for anything else — including a
     * plain, unfragmented envelope, which is exactly as much "not a fragment"
     * as garbage from a stranger on the same public topic. Never throws:
     * malformed input is the ordinary case for a public topic.
     */
    fun parse(wire: String): Fragment? {
        if (!wire.startsWith(PREFIX)) return null
        val rest = wire.removePrefix(PREFIX)

        val firstSep = rest.indexOf(SEPARATOR)
        if (firstSep <= 0) return null
        val groupId = rest.substring(0, firstSep)

        val secondSep = rest.indexOf(SEPARATOR, firstSep + 1)
        if (secondSep <= firstSep) return null
        val index = rest.substring(firstSep + 1, secondSep).toIntOrNull() ?: return null

        val thirdSep = rest.indexOf(SEPARATOR, secondSep + 1)
        if (thirdSep <= secondSep) return null
        val total = rest.substring(secondSep + 1, thirdSep).toIntOrNull() ?: return null

        if (total <= 0 || index !in 0 until total) return null

        val chunk = rest.substring(thirdSep + 1)
        if (chunk.isEmpty()) return null

        return Fragment(groupId, index, total, chunk)
    }
}

/**
 * Buffers fragments until every piece of a group has arrived, then hands back
 * the envelope [MessageFragments.split] cut apart.
 *
 * In memory only, not persisted. Every chunk of one group is published
 * back-to-back by the same call, so ordinary delivery reassembles within one
 * poll batch or a few seconds of an SSE stream. A group that never completes —
 * a lost chunk, or a stranger publishing fragment-shaped garbage to a public
 * topic — is left to age out; that is exactly as silent as an ordinary dropped
 * message already is on this transport, so nothing here tries to be more
 * reliable than the rest of the sync layer.
 *
 * Fragments carry no per-chunk authentication of their own (#249) — the
 * eventual AEAD open over the reassembled envelope is what actually proves a
 * message, not this class. What this class alone can and does guard against
 * is a race: an on-topic attacker who sees a real group id can try to fill an
 * unclaimed slot with a fragment of their own before the real one lands. Two
 * different fragments for the same slot are never legitimate — a sender mints
 * a fresh group id per publish — so the second one poisons the group rather
 * than either silently winning.
 *
 * [maxPendingGroups] and each fragment's own [MessageFragments.Fragment.total]
 * are both bounded (see [MAX_FRAGMENTS_PER_GROUP]) for the same reason
 * `NtfyTransport.MAX_MESSAGES_PER_POLL` exists: the topic is public, and
 * without a bound a hostile sender could grow this map without limit between
 * eviction sweeps.
 */
class FragmentAssembler(
    private val maxPendingGroups: Int = MAX_PENDING_GROUPS,
    private val maxAgeMillis: Long = TimeUnit.MINUTES.toMillis(5),
    private val now: () -> Instant = Instant::now,
) {

    private class Pending(val chunks: Array<String?>, val startedAt: Instant) {
        /** #249: set once two different fragments race for the same slot. */
        var poisoned = false
    }

    private val pending = LinkedHashMap<String, Pending>()

    /**
     * Feeds one wire string in.
     *
     * Returns [wire] itself when it is not a fragment at all, the reassembled
     * envelope once [wire] completes its group, or null while still waiting
     * (or after refusing something malformed, oversized, or poisoned).
     */
    fun accept(wire: String): String? {
        val fragment = MessageFragments.parse(wire) ?: return wire
        evictStale()

        if (fragment.total > MAX_FRAGMENTS_PER_GROUP) {
            pending.remove(fragment.groupId)
            return null
        }

        val entry = pending[fragment.groupId] ?: run {
            if (pending.size >= maxPendingGroups) return null
            Pending(arrayOfNulls(fragment.total), now()).also { pending[fragment.groupId] = it }
        }

        if (entry.chunks.size != fragment.total) {
            // A group id reused with a different total: never legitimate, since
            // one publish call picks one total for all its pieces. Discard
            // rather than guess which total to believe.
            pending.remove(fragment.groupId)
            return null
        }

        if (entry.poisoned) return null

        val existing = entry.chunks[fragment.index]
        if (existing != null) {
            if (existing != fragment.chunk) {
                // #249: two different fragments for the same slot — never
                // legitimate, since a sender mints a fresh group id per
                // publish rather than republishing one under the same id
                // with different content (SyncPublisher/MessageTransmitter's
                // own id). Whichever arrived first is no more trustworthy
                // than the second, so the group is quarantined rather than
                // silently kept or reset: no further chunk completes it
                // until it ages out through the ordinary evictStale sweep,
                // so an attacker cannot just retry immediately, and a
                // completed-but-wrong reassembly can never happen.
                entry.poisoned = true
            }
            return null
        }

        entry.chunks[fragment.index] = fragment.chunk
        if (entry.chunks.any { it == null }) return null

        pending.remove(fragment.groupId)
        return entry.chunks.joinToString("")
    }

    private fun evictStale() {
        val cutoff = now().minusMillis(maxAgeMillis)
        pending.entries.removeAll { it.value.startedAt.isBefore(cutoff) }
    }

    private companion object {
        const val MAX_PENDING_GROUPS = 50
        const val MAX_FRAGMENTS_PER_GROUP = 200
    }
}
