package dev.schrammel.dementor_watch.policy.request

import dev.schrammel.dementor_watch.model.AutoApproval
import dev.schrammel.dementor_watch.model.AutoApprovalWindow
import dev.schrammel.dementor_watch.model.Request
import dev.schrammel.dementor_watch.model.RequestState
import java.time.Instant

/** Why a request was, or was not, answered without a person. */
sealed interface AutoDecision {

    /** [rule] will answer this one. */
    data class Approve(val rule: AutoApproval) : AutoDecision

    /** Nobody's standing rule covers it; it waits for a parent. */
    data object Ask : AutoDecision
}

/**
 * Standing answers to requests (RQ-1).
 *
 * Pure, so the interesting parts — what counts as "twice a week", what happens
 * when two rules could answer, what a clock moved backwards does — are decided
 * here and tested without two phones.
 *
 * Every rule is a *cap*. A request larger than the cap, or one past the count for
 * the window, is not denied: it falls through to a person, which is the only
 * behaviour that leaves the negotiation loop intact.
 */
object AutoApprovalRules {

    /**
     * Which rule, if any, answers [request].
     *
     * [history] is every request the device knows about, including the one being
     * decided; already-counted approvals are found in it. [windowStart] pins the
     * day and week boundaries, supplied by the caller because only it knows the
     * family's day boundary hour.
     *
     * When several rules could answer, the one granting least wins. A family with
     * an overlapping pair has expressed two intentions, and honouring the tighter
     * of the two is the reading that cannot surprise them.
     */
    fun decide(
        request: Request,
        rules: List<AutoApproval>,
        history: List<Request>,
        dayStart: Instant,
        weekStart: Instant,
    ): AutoDecision {
        val candidates = rules
            .filter { it.enabled }
            .filter { it.covers(request.target) }
            .filter { request.amount <= it.maxAmount }
            .filter { hasRoom(it, request, history, dayStart, weekStart) }

        val chosen = candidates.minByOrNull { it.maxAmount } ?: return AutoDecision.Ask
        return AutoDecision.Approve(chosen)
    }

    /**
     * Whether [rule] has answers left in its window.
     *
     * Counts approvals this rule actually made, not approvals in general: a parent
     * saying yes by hand does not spend the standing rule's allowance, because the
     * allowance exists to bound what happens *without* them.
     */
    private fun hasRoom(
        rule: AutoApproval,
        request: Request,
        history: List<Request>,
        dayStart: Instant,
        weekStart: Instant,
    ): Boolean {
        val since = when (rule.window) {
            AutoApprovalWindow.DAY -> dayStart
            AutoApprovalWindow.WEEK -> weekStart
        }

        val used = history.count { past ->
            past.id != request.id &&
                past.state == RequestState.APPROVED &&
                past.autoApprovedBy == rule.id &&
                past.decidedAt?.isBefore(since) == false
        }
        return used < rule.maxPerWindow
    }

    /**
     * How an auto-approval signs itself in the request and the shared history.
     *
     * The rule's id rather than a bare "automatic": a family with two standing
     * rules needs to know which one answered, and the count above needs to tell
     * one rule's approvals from another's.
     */
    fun decidedBy(rule: AutoApproval): String = RequestRules.AUTOMATIC_PREFIX + rule.id

    /** Whether a settled request was answered by a standing rule rather than a person. */
    fun wasAutomatic(request: Request): Boolean = request.autoApprovedBy != null
}
