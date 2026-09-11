package dev.schrammel.protego_parentis.sync.core

/**
 * The replicated state every device in a family converges on.
 *
 * ## Why this shape
 *
 * The sync layer used to be a set of hand-written request/response exchanges —
 * "ask the child for its rules", "push the whole policy back". That had three
 * problems this replaces:
 *
 * - **Whole-state messages.** Changing one limit published every rule, every
 *   category and every per-app decision. On a metered connection, repeatedly.
 * - **Ask-then-answer.** Nothing arrived until somebody opened a screen that
 *   asked for it, so two phones could disagree indefinitely and neither knew.
 * - **Last write clobbers.** A push built from a stale copy overwrote whatever
 *   had changed in the meantime, so the only safe thing to do was refuse it.
 *
 * ## The primitive
 *
 * Everything reduces to one thing: a **register at a path**, carrying a value,
 * the logical time it was written, and which device wrote it. A whole policy, a
 * roster, a chat log and a request queue are all just sets of paths:
 *
 * ```
 * policy/dailyLimitMinutes            = "120"
 * policy/categories/games/name        = "Games"
 * policy/categories/games/packages/com.example.a = "1"
 * chat/019283/text                    = "dinner in ten"
 * ```
 *
 * Collections are expressed as membership registers rather than as encoded
 * lists — the path *is* the key. That is what makes two parents editing
 * different apps in the same category both survive: they write different paths,
 * so there is nothing to resolve. Only genuinely concurrent writes to the *same*
 * path need a rule, and there the later logical time wins, ties broken by device
 * id so that every replica breaks them identically.
 *
 * Deletion is a tombstone — a register whose value is null — because a replica
 * that has not yet heard of a key cannot tell "deleted" from "never existed",
 * and simply dropping the entry would let an older write resurrect it.
 *
 * ## What travels
 *
 * Operations, not state: `(path, value, lamport, origin)`. One edit is one small
 * op. They are commutative and idempotent under [StateMerge.apply], so they may
 * arrive out of order, more than once, or in a batch after an hour offline, and
 * every replica lands on the same map.
 *
 * Nothing here knows about transports, encryption, roles or Android. Ordering
 * and convergence are decided by the values in the ops themselves, which is what
 * lets the whole engine be tested without a device — see the tests alongside it.
 */

/** One replicated register: what it holds, when it was written, and by whom. */
data class StateEntry(
    /** Null is a tombstone: the key was deleted, and that fact is itself a write. */
    val value: String?,
    val lamport: Long,
    /** The device id that wrote it. Only ever used to break ties deterministically. */
    val origin: String,
) {
    val isTombstone: Boolean get() = value == null
}

/** One write, as it travels between devices. */
data class StateOp(
    val path: String,
    val value: String?,
    val lamport: Long,
    val origin: String,
) {
    fun toEntry(): StateEntry = StateEntry(value, lamport, origin)
}

/**
 * A Lamport clock.
 *
 * Wall-clock time is not usable for ordering here for the same reason it is not
 * usable for the PIN lockout: on the managed phone it is settable by the person
 * the rules are about, and a device whose clock ran a year fast would win every
 * conflict forever. A logical clock only ever counts events this replica has
 * actually seen.
 */
class LamportClock(start: Long = 0L) {

    var value: Long = start
        private set

    /** The time to stamp a new local write with. */
    @Synchronized
    fun tick(): Long = ++value

    /**
     * Folds in a time seen from elsewhere, so later local writes sort after it.
     *
     * A claimed time beyond [CEILING] is ignored. Every device chooses its own
     * clock values, so a hostile one could otherwise send `Long.MAX_VALUE`,
     * every replica would adopt it, and the next [tick] on all of them would
     * overflow into negative numbers — at which point ordering inverts and the
     * whole family's state resolves backwards. The ceiling is many orders of
     * magnitude above anything a family will reach by counting its own edits,
     * so nothing legitimate ever meets it.
     */
    @Synchronized
    fun observe(seen: Long) {
        if (seen > CEILING) return
        if (seen > value) value = seen
    }

    companion object {
        /**
         * The largest clock value worth believing.
         *
         * Roughly a thousand million million: unreachable by counting edits,
         * and far enough below [Long.MAX_VALUE] that ticking on from it cannot
         * overflow.
         */
        const val CEILING = 1L shl 50
    }
}

/**
 * Merging ops into state.
 *
 * Pure, deterministic and order-independent: the same set of ops applied in any
 * sequence, any number of times, produces the same map. That is the property the
 * whole design rests on, and it is what the tests exercise directly.
 *
 * ## Why append-only is decided here and not by the receiver
 *
 * The obvious way to make a log unrevisable is to refuse a write to a path that
 * already holds something. That is what this used to do, and it was wrong in a
 * way that is easy to miss: whether a path "already holds something" depends on
 * what this replica happens to have seen *so far*. A replica that received the
 * revision first would keep the revision; one that received the original first
 * would keep the original. They would then disagree permanently, and — because
 * each keeps refusing the other's value — no amount of repair could reconcile
 * them. A rule meant to protect the log could split it.
 *
 * Deciding it here instead makes it a property of the two ops rather than of
 * arrival order, so every replica reaches the same answer independently.
 */
object StateMerge {

    /**
     * Whether [incoming] should replace [existing].
     *
     * Later logical time wins. Equal time is a genuine concurrent write, broken
     * by device id — arbitrary, but *identically* arbitrary on every replica,
     * which is the only thing that matters. An op equal in both is the same op
     * arriving twice and changes nothing.
     *
     * Append-only paths invert the comparison: the *earliest* write is the one
     * that stands, so a later one cannot revise it. Inverted rather than
     * refused, and that distinction is the whole point — see below.
     */
    fun wins(incoming: StateOp, existing: StateEntry?): Boolean {
        if (existing == null) return true
        val earliestWins = ReplicationRules.isAppendOnly(incoming.path)
        if (incoming.lamport != existing.lamport) {
            return if (earliestWins) {
                incoming.lamport < existing.lamport
            } else {
                incoming.lamport > existing.lamport
            }
        }
        return if (earliestWins) incoming.origin < existing.origin else incoming.origin > existing.origin
    }

    fun apply(state: Map<String, StateEntry>, ops: Iterable<StateOp>): Map<String, StateEntry> {
        var changed = false
        val merged = state.toMutableMap()
        ops.forEach { op ->
            if (wins(op, merged[op.path])) {
                merged[op.path] = op.toEntry()
                changed = true
            }
        }
        return if (changed) merged else state
    }

    /** Only the ops that would actually change [state] — what is worth storing or replying with. */
    fun novel(state: Map<String, StateEntry>, ops: Iterable<StateOp>): List<StateOp> =
        ops.filter { wins(it, state[it.path]) }

    /** Every entry as an op again, for answering a repair request. */
    fun opsOf(state: Map<String, StateEntry>): List<StateOp> =
        state.map { (path, entry) -> StateOp(path, entry.value, entry.lamport, entry.origin) }
}
