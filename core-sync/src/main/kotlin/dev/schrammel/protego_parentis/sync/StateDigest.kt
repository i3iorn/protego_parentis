package dev.schrammel.protego_parentis.sync

/**
 * How a device notices it has fallen behind, without anybody asking.
 *
 * Deltas alone are not enough. The relay is a dumb store with a retention
 * window — ntfy's is hours, not days — so a phone that was flat over a weekend
 * comes back to find the ops it missed simply gone. Nothing in the op stream
 * itself can tell it that: an empty poll and a poll that missed everything look
 * identical.
 *
 * So every device periodically says what it *has*, cheaply. Paths are sorted
 * into a fixed number of buckets by hash, and each bucket is reduced to one
 * number. A digest is therefore [BUCKETS] numbers regardless of whether the
 * family has ten registers or ten thousand — a few hundred bytes, published on a
 * slow timer, and in the common case where everyone agrees it is the end of the
 * matter.
 *
 * When two digests differ, they differ only in the buckets that actually
 * disagree, so the repair asks for those and not for the whole state. The
 * expensive exchange is bounded by what is genuinely out of step rather than by
 * the size of the family's history.
 *
 * Not everything is compared. A section that changes every few seconds would
 * never match, so every exchange would find a difference and ask for a repair —
 * spending far more than it saved. What is in and what is out is decided by
 * [ReplicationRules.isDigested].
 *
 * This is the coarsest useful form of a Merkle comparison. A tree would narrow
 * further at the cost of several more round trips, which on a polled transport
 * are minutes each — for a state this size, one round of sixteen buckets is the
 * better trade.
 */
object StateDigest {

    /**
     * Sixteen buckets.
     *
     * Enough that one stale register does not drag the whole state into a
     * repair, small enough that the digest stays a single short message. The
     * value is part of the wire format: two devices must bucket identically to
     * compare at all, so changing it is a protocol change, not a tuning knob.
     */
    const val BUCKETS = 16

    /**
     * Which bucket a path falls in.
     *
     * Deliberately our own arithmetic rather than [String.hashCode]: the JVM's
     * is specified, but relying on a platform hash for something two independent
     * devices must agree on invites a divergence that would show up as an
     * unrepairable digest mismatch and nothing else.
     */
    fun bucketOf(path: String): Int {
        var hash = 0
        path.forEach { character -> hash = hash * 31 + character.code }
        return ((hash % BUCKETS) + BUCKETS) % BUCKETS
    }

    /**
     * One number per bucket, summarising every entry in it.
     *
     * Combined with exclusive-or so the result does not depend on the order
     * entries happen to be held in — two replicas that agree must produce the
     * same digest even though nothing keeps their maps in the same sequence.
     * Value, time and origin all feed in, so a bucket whose entries differ in
     * any of the three shows up as different.
     */
    fun compute(state: Map<String, StateEntry>, nowSeconds: Long): List<Long> {
        val buckets = LongArray(BUCKETS)
        state.forEach { (path, entry) ->
            if (!ReplicationRules.isDigested(path, nowSeconds)) return@forEach
            buckets[bucketOf(path)] = buckets[bucketOf(path)] xor fingerprint(path, entry)
        }
        return buckets.toList()
    }

    /** The buckets where two digests disagree, and so the only ones worth repairing. */
    fun differing(mine: List<Long>, theirs: List<Long>): Set<Int> {
        // A digest from a build with a different bucket count cannot be compared
        // at all. Treating it as "everything differs" would ask for a full repair
        // on every exchange, forever; ignoring it leaves the two to converge
        // through ordinary ops, which still works.
        if (mine.size != theirs.size) return emptySet()
        return mine.indices.filter { mine[it] != theirs[it] }.toSet()
    }

    /**
     * Every entry whose path falls in one of [buckets] — the answer to a repair
     * request.
     *
     * Filtered by the same rule the digest uses, so a repair never carries what
     * the digest did not compare. Sending an undigested entry would be pure
     * waste: the asking replica did not miss it, because it was never counted.
     */
    fun entriesIn(
        state: Map<String, StateEntry>,
        buckets: Set<Int>,
        nowSeconds: Long,
    ): List<StateOp> =
        state.filterKeys { bucketOf(it) in buckets && ReplicationRules.isDigested(it, nowSeconds) }
            .map { (path, entry) -> StateOp(path, entry.value, entry.lamport, entry.origin) }

    private fun fingerprint(path: String, entry: StateEntry): Long {
        var hash = FNV_OFFSET
        fun mix(text: String) {
            text.forEach { character ->
                hash = hash xor character.code.toLong()
                hash *= FNV_PRIME
            }
            // A separator, so ("ab","c") and ("a","bc") do not collide.
            hash = hash xor SEPARATOR
            hash *= FNV_PRIME
        }

        mix(path)
        // A tombstone is a real state a replica can hold, and it has to be
        // distinguishable from an empty string. Mixed as its own field rather
        // than as a stand-in value, because any stand-in is a string somebody
        // could legitimately have stored.
        mix(if (entry.isTombstone) "1" else "0")
        mix(entry.value.orEmpty())
        mix(entry.lamport.toString())
        mix(entry.origin)
        return hash
    }

    private const val FNV_OFFSET = -3750763034362895579L
    private const val FNV_PRIME = 1099511628211L
    private const val SEPARATOR = 0x1FL
}
