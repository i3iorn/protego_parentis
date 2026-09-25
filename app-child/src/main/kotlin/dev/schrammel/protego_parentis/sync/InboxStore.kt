package dev.schrammel.protego_parentis.sync

import android.content.Context
import dev.schrammel.protego_parentis.model.RequestState
import java.time.Instant

/**
 * A request as a parent phone sees it.
 *
 * Deliberately not the domain `Request`. A parent phone genuinely knows less: it
 * has no copy of the child's installed apps or categories, so it cannot resolve a
 * package name into something a human would recognise. The child sends a label
 * along with the request, and this type is honest about the fact that the label
 * is all the parent has.
 */
data class IncomingRequest(
    val id: String,
    /** Human text supplied by the child's phone: an app name, a category, or "". */
    val label: String,
    val minutes: Int,
    val reason: String,
    val raisedAt: Instant,
    val expiresAt: Instant,
    val state: RequestState,
    val decidedAt: Instant? = null,
    val decisionNote: String? = null,
) {
    fun isOpenAt(now: Instant): Boolean = state.isOpen && now.isBefore(expiresAt)
}

/**
 * What the parent phone has heard from the child.
 *
 * Kept separate from `RequestStore`, which holds the child's own requests. Sharing
 * one store would mean a parent phone's inbox and a child phone's outbox occupying
 * the same file with different meanings — the sort of overloading that produces a
 * bug nobody can reproduce.
 *
 * Reads settle anything that has run out of time, so a request never sits in the
 * inbox looking answerable hours after the child gave up on it.
 */
class InboxStore(context: Context) {

    private val appContext = context.applicationContext
    private val replication = RequestReplication(appContext)

    /**
     * Everything the parent has heard about, newest first.
     *
     * Expiry is computed on read rather than written back. Under replication a
     * lapse is not an event anybody caused — every replica can work it out from
     * the expiry it already holds, and writing it would be three phones racing
     * to record the same non-decision.
     */
    fun all(now: Instant = Instant.now()): List<IncomingRequest> =
        replication.all().map { request ->
            IncomingRequest(
                id = request.id,
                label = request.targetLabel,
                minutes = request.minutes,
                reason = request.reason,
                raisedAt = request.at,
                expiresAt = request.expiresAt,
                state = if (request.state.isOpen && !now.isBefore(request.expiresAt)) {
                    RequestState.EXPIRED
                } else {
                    request.state
                },
                decidedAt = request.decidedAt,
                decisionNote = request.note,
            )
        }.sortedByDescending { it.raisedAt }

    fun pending(now: Instant = Instant.now()): List<IncomingRequest> =
        all(now).filter { it.isOpenAt(now) }

    /**
     * Answers a request.
     *
     * Returns false when it was already settled, which is what stops a second
     * parent's tap counting twice. Note that this is a courtesy rather than a
     * guarantee: two parents answering within one poll of each other both see it
     * open and both write. That is no longer a problem the way it was — both
     * write the same register, so every replica converges on the same answer
     * instead of each phone believing whichever arrived last.
     */
    fun settle(
        id: String,
        state: RequestState,
        note: String?,
        decidedBy: String,
    ): Boolean {
        // Resolved by either id. A request has two: the one the replica knows it
        // by, and the one the phone that raised it knows it by. A decision made
        // standing at the managed phone — or by one of its standing rules —
        // travels as the older message type and carries the *local* id, so
        // matching only on the replicated one left those requests showing as
        // pending on every parent's phone forever.
        val target = replication.all().firstOrNull { it.id == id || it.localId == id }
            ?: return false
        if (!target.state.isOpen) return false
        if (!replication.decide(target.id, state, note, decidedBy)) return false

        // Sent now rather than at the next poll. A parent phone has no
        // enforcement tick, so its next poll can be a quarter of an hour away —
        // and an approval that appears to do nothing for fifteen minutes is one
        // nobody trusts the second time.
        SyncGraph.get(appContext).publishPendingOps()
        return true
    }

}
