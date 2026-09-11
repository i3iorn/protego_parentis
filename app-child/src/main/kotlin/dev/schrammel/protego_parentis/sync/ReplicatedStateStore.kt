package dev.schrammel.protego_parentis.sync

import android.content.Context
import androidx.core.content.edit
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.sync.core.LamportClock
import dev.schrammel.protego_parentis.sync.core.ReplicationRules
import dev.schrammel.protego_parentis.sync.core.StateCodec
import dev.schrammel.protego_parentis.sync.core.StateDigest
import dev.schrammel.protego_parentis.sync.core.StateEntry
import dev.schrammel.protego_parentis.sync.core.StateMerge
import dev.schrammel.protego_parentis.sync.core.StateOp
import dev.schrammel.protego_parentis.sync.core.SyncRole
import java.time.Instant

/**
 * This device's copy of the family's replicated state, on disk.
 *
 * One map of path to [StateEntry], plus the logical clock that stamps local
 * writes and the queue of ops not yet published. Everything about *how* writes
 * merge lives in [StateMerge]; this holds them and nothing more.
 *
 * ## The outbound queue
 *
 * A write made in a tunnel still has to reach the family. Ops are queued here
 * when they are made and cleared only once the transport has actually accepted
 * them, so a change survives the app being killed before it was published. The
 * queue is bounded: a phone offline for a month should not accumulate an
 * unbounded backlog, and it does not need to — the digest exchange repairs
 * whatever the queue had to drop, which is precisely what it is for.
 *
 * ## Tombstones
 *
 * Deleted keys are kept as tombstones forever, which is a real and deliberate
 * leak. Collecting them safely needs every replica to have acknowledged the
 * deletion, and there is no acknowledgement in a fire-and-forget relay protocol.
 * At this state's size — a few hundred registers for a family — that is a cost
 * worth paying for correctness. It would need revisiting before this carried
 * something genuinely large, such as full usage history.
 */
class ReplicatedStateStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /**
     * The logical clock, shared by every instance in the process.
     *
     * Emphatically not per-instance, which it was, and which was a silent and
     * very confusing bug. Half a dozen collaborators build their own store —
     * the publisher, the receiver, and each of the section adapters — and each
     * read the clock off disk when it happened to be constructed. A long-lived
     * one therefore held a stale value, and a stale clock is worse than a wrong
     * one: a local edit stamped with a lamport below what the map already held
     * lost its own merge, so the write did nothing, was still published, and was
     * refused by every peer for the same reason. The edit vanished with nothing
     * logged anywhere. Committing then wrote the stale value back, dragging the
     * persisted clock *backwards* so the next writer collided too.
     *
     * [LOCK] was already process-wide for exactly this class of problem; the
     * clock simply was not held under it.
     */
    private val clock: LamportClock get() = shared(prefs)

    /** Every register this device holds. */
    val entries: Map<String, StateEntry>
        get() = synchronized(LOCK) { read() }

    /** Ops made here that the transport has not confirmed yet. */
    val pending: List<StateOp>
        get() = synchronized(LOCK) { StateCodec.decodeOps(prefs.getString(KEY_PENDING, null)) }

    fun value(path: String): String? = entries[path]?.value

    /** Every live value directly under [prefix], keyed by the segment after it. */
    fun children(prefix: String): Map<String, String> {
        val root = prefix.trimEnd(ReplicationRules.SEPARATOR) + ReplicationRules.SEPARATOR
        return entries
            .filterKeys { it.startsWith(root) && !it.removePrefix(root).contains(ReplicationRules.SEPARATOR) }
            .mapNotNull { (path, entry) -> entry.value?.let { path.removePrefix(root) to it } }
            .toMap()
    }

    /** The distinct ids one level under [prefix], ignoring keys whose whole subtree is gone. */
    fun idsUnder(prefix: String): Set<String> {
        val root = prefix.trimEnd(ReplicationRules.SEPARATOR) + ReplicationRules.SEPARATOR
        return entries
            .filterValues { !it.isTombstone }
            .keys
            .filter { it.startsWith(root) }
            .mapNotNull { it.removePrefix(root).substringBefore(ReplicationRules.SEPARATOR).takeIf(String::isNotEmpty) }
            .toSet()
    }

    /**
     * Records a local change and queues it to be published.
     *
     * Writes nothing and queues nothing when the value is already what it would
     * be set to — otherwise every screen that re-renders would republish its
     * whole section, which is exactly the traffic this design exists to remove.
     */
    fun write(path: String, value: String?, origin: String, role: SyncRole): StateOp? =
        writeAll(mapOf(path to value), origin, role).singleOrNull()

    /** As [write], for several paths at once, sharing one clock tick and one commit. */
    fun writeAll(values: Map<String, String?>, origin: String, role: SyncRole): List<StateOp> = synchronized(LOCK) {
        val current = read()
        val changed = values
            // Held to exactly the rule a receiver applies, and that symmetry is
            // the point. Writing something locally that every peer would refuse
            // is the worst available outcome: this replica would hold a value
            // nobody else accepts, in a section the digest compares, and no
            // amount of repair could reconcile them. An over-long app label is
            // enough to cause it, so it is checked here rather than trusted.
            .filterKeys { ReplicationRules.isWellFormedPath(it) }
            // The same authorisation a peer's write is held to, applied to this
            // device's own writes. Without this a write this device is not
            // allowed to make never fails loudly — it is accepted here, queued,
            // published, and then refused by every other replica forever, so
            // the two never agree and the digest asks for the same doomed
            // repair on every exchange. See [ReplicationRules.mayWrite].
            .filterKeys { ReplicationRules.mayWrite(role, it, DeviceId(origin)) }
            .filterKeys { ReplicationRules.mayWriteRequestField(role, it) }
            .filter { (path, value) -> ReplicationRules.mayWriteConsensusField(path, value, DeviceId(origin)) }
            .filter { (_, value) -> (value?.length ?: 0) <= ReplicationRules.MAX_VALUE_LENGTH }
            .filter { (path, value) -> current[path]?.value != value }
        if (changed.isEmpty()) return emptyList()

        val lamport = clock.tick()
        val ops = changed.map { (path, value) -> StateOp(path, value, lamport, origin) }
        commit(StateMerge.apply(current, ops), queue = pending + ops)
        return ops
    }

    /**
     * Folds in ops from elsewhere, and returns the ones that actually changed
     * something — what a caller needs in order to decide whether to re-render or
     * re-apply enforcement.
     */
    fun receive(ops: List<StateOp>): List<StateOp> = synchronized(LOCK) {
        val current = read()
        val novel = StateMerge.novel(current, ops)
        // Even a rejected op moves the clock: it proves somebody has reached a
        // logical time this device had not, and local writes must sort after it.
        ops.forEach { clock.observe(it.lamport) }
        if (novel.isEmpty()) {
            prefs.edit { putLong(KEY_CLOCK, clock.value) }
            return emptyList()
        }
        commit(StateMerge.apply(current, novel), queue = pending)
        return novel
    }

    /**
     * Drops ops the transport has accepted.
     *
     * A block body rather than an expression one, because of the early return:
     * Kotlin warns that returning from an expression-bodied function without a
     * declared return type stops compiling in language version 2.5.
     */
    fun clearPending(published: List<StateOp>) {
        synchronized(LOCK) {
            if (published.isEmpty()) return
            val sent = published.map { it.path to it.lamport }.toSet()
            writePending(pending.filterNot { (it.path to it.lamport) in sent })
        }
    }

    /** This device's summary, for the digest exchange. */
    fun digest(nowSeconds: Long = nowSeconds()): List<Long> =
        StateDigest.compute(entries, nowSeconds)

    /** Everything in [buckets], as ops, for answering a repair request. */
    fun entriesIn(buckets: Set<Int>, nowSeconds: Long = nowSeconds()): List<StateOp> =
        StateDigest.entriesIn(entries, buckets, nowSeconds)

    /**
     * Drops stream entries old enough that nothing compares them any more.
     *
     * Bounded growth is the point: without this, a family's chat and request
     * history would accumulate in every replica forever, and every repair would
     * carry more of it. Safe precisely because the pruning horizon is well past
     * the digest window — an entry leaves the comparison long before anything
     * deletes it, so no replica can see a pruned entry as a difference it needs
     * to repair.
     *
     * Deleted outright rather than tombstoned. A tombstone would defeat the
     * purpose by keeping a register per pruned entry forever, and it is not
     * needed: an ancient entry is outside the window on every replica, so
     * nothing will hand it back.
     */
    fun prune(nowSeconds: Long = nowSeconds()): Int = synchronized(LOCK) {
        val current = read()
        val stale = current.keys.filter { ReplicationRules.isPrunable(it, nowSeconds) }
        if (stale.isEmpty()) return 0
        commit(current - stale.toSet(), queue = pending)
        return stale.size
    }

    private fun nowSeconds(): Long = Instant.now().epochSecond

    /**
     * Forgets the whole replica. Used when this phone leaves the family.
     *
     * The clock is dropped with it. Carrying a high one into the next family
     * would make every write from this phone outrank everything already there —
     * a phone that had been busy elsewhere would join and quietly win every
     * conflict, including clobbering rules it had never seen.
     */
    fun forgetAll() = synchronized(LOCK) {
        prefs.edit { clear() }
        sharedClock = null
    }

    private fun read(): Map<String, StateEntry> =
        StateCodec.decodeOps(prefs.getString(KEY_ENTRIES, null))
            .associate { it.path to it.toEntry() }

    private fun commit(state: Map<String, StateEntry>, queue: List<StateOp>) {
        prefs.edit {
            putString(KEY_ENTRIES, StateCodec.encodeOps(StateMerge.opsOf(state)))
            putLong(KEY_CLOCK, clock.value)
        }
        writePending(queue)
    }

    private fun writePending(queue: List<StateOp>) {
        prefs.edit { putString(KEY_PENDING, StateCodec.encodeOps(queue.takeLast(MAX_PENDING))) }
    }

    private companion object {
        const val FILE_NAME = "replicated_state"
        const val KEY_ENTRIES = "entries"
        const val KEY_PENDING = "pending"
        const val KEY_CLOCK = "clock"

        /**
         * Enough to hold a long editing session offline; far short of unbounded.
         * Anything dropped here is recovered by the digest exchange rather than
         * lost, which is why a bound is safe at all.
         */
        const val MAX_PENDING = 500

        val LOCK = Any()

        /**
         * One clock for the process, built from disk the first time it is asked
         * for and shared by every store afterwards.
         *
         * Read under [LOCK] like everything else here, so a caller cannot
         * observe it half-initialised.
         */
        @Volatile
        private var sharedClock: LamportClock? = null

        fun shared(prefs: android.content.SharedPreferences): LamportClock =
            sharedClock ?: synchronized(LOCK) {
                sharedClock ?: LamportClock(prefs.getLong(KEY_CLOCK, 0L)).also { sharedClock = it }
            }
    }
}
