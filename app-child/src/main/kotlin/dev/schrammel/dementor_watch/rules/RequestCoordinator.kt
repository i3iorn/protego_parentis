package dev.schrammel.dementor_watch.rules

import android.content.Context
import dev.schrammel.dementor_watch.enforcement.RequestNotifier
import dev.schrammel.dementor_watch.model.GrantId
import dev.schrammel.dementor_watch.model.GrantTarget
import dev.schrammel.dementor_watch.model.Request
import dev.schrammel.dementor_watch.model.RequestId
import dev.schrammel.dementor_watch.model.RequestState
import dev.schrammel.dementor_watch.policy.PolicyClock
import dev.schrammel.dementor_watch.policy.request.RequestRules
import dev.schrammel.dementor_watch.policy.request.SubmitDecision
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Runs the ask-and-answer loop (RQ-1).
 *
 * Sits between the pure [RequestRules] and the stores, and is the only place a
 * request changes state. Approval writes the request and its grant together —
 * splitting them would allow a request marked approved with no time actually
 * granted, which is the one outcome guaranteed to destroy trust in the feature.
 */
class RequestCoordinator(
    context: Context,
    /**
     * Called when a request is raised, so it can be forwarded to the parent
     * phones. A callback rather than a direct dependency because the sync layer
     * also calls back into this class, and wiring them together directly would
     * be a construction loop.
     */
    private val onRaised: ((Request) -> Unit)? = null,
) {

    private val appContext = context.applicationContext
    private val requests = RequestStore(appContext)
    private val grants = GrantStore(appContext)
    private val policyStore = LocalPolicyStore(appContext)
    private val notifier = RequestNotifier(appContext)

    fun all(now: Instant = Instant.now()): List<Request> = requests.all(now)

    fun pending(now: Instant = Instant.now()): List<Request> = requests.pending(now)

    /** Whether the child may ask for [target] right now, and why not if they may not. */
    fun canSubmit(target: GrantTarget, now: Instant = Instant.now()): SubmitDecision =
        RequestRules.canSubmit(
            existing = requests.all(now),
            target = target,
            now = now,
            decidedSince = startOfToday(now),
        )

    /**
     * Records a new request. Returns null when the rules refuse it — callers
     * should have checked [canSubmit] first and shown the reason.
     */
    fun submit(
        target: GrantTarget,
        amount: Duration,
        reason: String,
        now: Instant = Instant.now(),
    ): Request? {
        if (!canSubmit(target, now).isAllowed) return null

        val request = Request(
            id = RequestId(UUID.randomUUID().toString()),
            target = target,
            amount = amount,
            reason = reason.trim(),
            state = RequestState.PENDING,
            createdAt = now,
            expiresAt = now.plusMillis(RequestRules.DEFAULT_LIFETIME.inWholeMilliseconds),
        )
        requests.save(request)
        notifier.notifyRequestReceived(request)
        onRaised?.invoke(request)
        return request
    }

    /**
     * Approves a request and applies the time it asked for, in one step.
     *
     * The grant expires at the day boundary like any other bonus time: an
     * exception is for tonight, not a permanent change to the rules.
     */
    fun approve(id: RequestId, note: String? = null, now: Instant = Instant.now()): Boolean {
        val request = requests.find(id, now)?.takeIf { it.state.isOpen } ?: return false

        val (approved, grant) = RequestRules.approve(
            request = request,
            now = now,
            by = DECIDED_BY_PARENT,
            grantExpiresAt = endOfToday(now),
            grantId = GrantId(UUID.randomUUID().toString()),
            note = note?.trim()?.takeIf(String::isNotEmpty),
        )
        grants.add(grant)
        requests.save(approved)
        notifier.notifyDecision(approved)
        return true
    }

    fun deny(id: RequestId, note: String? = null, now: Instant = Instant.now()): Boolean {
        val request = requests.find(id, now)?.takeIf { it.state.isOpen } ?: return false
        val denied = RequestRules.deny(
            request = request,
            now = now,
            by = DECIDED_BY_PARENT,
            note = note?.trim()?.takeIf(String::isNotEmpty),
        )
        requests.save(denied)
        notifier.notifyDecision(denied)
        return true
    }

    fun withdraw(id: RequestId, now: Instant = Instant.now()): Boolean {
        val request = requests.find(id, now)?.takeIf { it.state.isOpen } ?: return false
        requests.save(RequestRules.withdraw(request, now))
        return true
    }

    /** The amounts the compose screen offers, mirroring the bonus-time options. */
    fun suggestedAmounts(): List<Duration> =
        appContext.resources
            .getIntArray(dev.schrammel.dementor_watch.R.array.bonus_minute_options)
            .map { it.minutes }

    private fun startOfToday(now: Instant): Instant {
        val policy = policyStore.load()
        val day = PolicyClock.policyDay(now, policy.timeZone, policy.dayBoundaryHour)
        return PolicyClock.dayStart(day, policy.timeZone, policy.dayBoundaryHour)
    }

    private fun endOfToday(now: Instant): Instant {
        val policy = policyStore.load()
        val day = PolicyClock.policyDay(now, policy.timeZone, policy.dayBoundaryHour)
        return PolicyClock.dayEnd(day, policy.timeZone, policy.dayBoundaryHour)
    }

    private companion object {
        /** Until the server exists, the answer comes from whoever holds the PIN. */
        const val DECIDED_BY_PARENT = "parent"
    }
}
