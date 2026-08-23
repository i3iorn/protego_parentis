package dev.schrammel.dementor_watch.policy.request

import dev.schrammel.dementor_watch.model.Grant
import dev.schrammel.dementor_watch.model.GrantId
import dev.schrammel.dementor_watch.model.GrantTarget
import dev.schrammel.dementor_watch.model.Request
import dev.schrammel.dementor_watch.model.RequestState
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/** Whether a new request may be made, and if not, why. */
sealed interface SubmitDecision {

    data object Allowed : SubmitDecision

    /** One is already waiting for the same thing; nagging is not a second ask. */
    data object AlreadyPending : SubmitDecision

    /**
     * Repeatedly denied for the same thing today. The UI turns this into a nudge
     * to go and talk rather than a wall — the point is to stop a request loop
     * becoming a way to wear a parent down.
     */
    data class TooManyDenials(val deniedToday: Int) : SubmitDecision

    val isAllowed: Boolean get() = this is Allowed
}

/**
 * The rules around asking for an exception (RQ-1).
 *
 * Kept pure and separate from storage so the awkward parts — expiry, rate
 * limiting, and turning an approval into a grant — can be tested directly. The
 * failure this most guards against is a request that is neither answered nor
 * closed, leaving a child staring at "pending" forever.
 */
object RequestRules {

    /**
     * How long a request waits for an answer.
     *
     * Two hours, so an unanswered evening request does not still be hanging over
     * breakfast. Expiry is deliberately not a denial: nobody said no.
     */
    val DEFAULT_LIFETIME: Duration = 2.hours

    /** Denials for the same target in one day before the UI suggests talking instead. */
    const val MAX_DENIALS_PER_DAY = 3

    /**
     * Closes any request whose time ran out. Returns the full list so callers can
     * store it back wholesale; unaffected requests are returned unchanged.
     */
    fun expire(requests: List<Request>, now: Instant): List<Request> = requests.map { request ->
        if (request.hasExpired(now)) {
            request.copy(state = RequestState.EXPIRED, decidedAt = now)
        } else {
            request
        }
    }

    /**
     * Whether the child may ask for [target] right now.
     *
     * [decidedSince] should be the start of the current policy day, so the denial
     * count resets with the day rather than accumulating forever.
     */
    fun canSubmit(
        existing: List<Request>,
        target: GrantTarget,
        now: Instant,
        decidedSince: Instant,
    ): SubmitDecision {
        val live = expire(existing, now)

        if (live.any { it.state.isOpen && it.target == target }) return SubmitDecision.AlreadyPending

        val denied = live.count { request ->
            request.state == RequestState.DENIED &&
                request.target == target &&
                request.decidedAt?.isBefore(decidedSince) == false
        }
        return if (denied >= MAX_DENIALS_PER_DAY) {
            SubmitDecision.TooManyDenials(denied)
        } else {
            SubmitDecision.Allowed
        }
    }

    /**
     * Approves [request] and produces the grant that carries it out.
     *
     * The two are returned together because they must be applied together: a
     * request marked approved with no matching grant is a promise the device does
     * not keep, and is exactly the bug that destroys trust in the feature.
     */
    fun approve(
        request: Request,
        now: Instant,
        by: String,
        grantExpiresAt: Instant,
        grantId: GrantId,
        note: String? = null,
    ): Pair<Request, Grant> {
        require(request.state.isOpen) { "only a pending request can be approved" }

        val approved = request.copy(
            state = RequestState.APPROVED,
            decidedAt = now,
            decidedBy = by,
            decisionNote = note,
        )
        val grant = Grant(
            id = grantId,
            target = request.target,
            amount = request.amount,
            grantedAt = now,
            expiresAt = grantExpiresAt,
            grantedBy = by,
            sourceRequestId = request.id.value,
        )
        return approved to grant
    }

    fun deny(request: Request, now: Instant, by: String, note: String? = null): Request {
        require(request.state.isOpen) { "only a pending request can be denied" }
        return request.copy(
            state = RequestState.DENIED,
            decidedAt = now,
            decidedBy = by,
            decisionNote = note,
        )
    }

    fun withdraw(request: Request, now: Instant): Request {
        require(request.state.isOpen) { "only a pending request can be withdrawn" }
        return request.copy(state = RequestState.WITHDRAWN, decidedAt = now)
    }
}
