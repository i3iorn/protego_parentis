package dev.schrammel.protego_parentis.rules

import android.content.Context
import dev.schrammel.protego_parentis.audit.Audit
import dev.schrammel.protego_parentis.enforcement.RequestNotifier
import dev.schrammel.protego_parentis.model.AuditAction
import dev.schrammel.protego_parentis.model.AuditActor
import dev.schrammel.protego_parentis.model.GrantId
import dev.schrammel.protego_parentis.model.GrantTarget
import dev.schrammel.protego_parentis.model.Request
import dev.schrammel.protego_parentis.model.RequestId
import dev.schrammel.protego_parentis.model.RequestState
import dev.schrammel.protego_parentis.policy.PolicyClock
import dev.schrammel.protego_parentis.policy.request.AutoApprovalRules
import dev.schrammel.protego_parentis.policy.request.AutoDecision
import dev.schrammel.protego_parentis.policy.request.RequestRules
import dev.schrammel.protego_parentis.policy.request.SubmitDecision
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
    /**
     * Called when a request is answered here, so the other parent phones learn of
     * it. Deliberately not invoked when a decision arrives *from* sync — that
     * would echo it straight back and the phones would answer each other forever.
     */
    private val onDecided: ((Request) -> Unit)? = null,
    /**
     * Called when a standing rule answered instead of a person, so the parents'
     * inboxes can settle rather than showing a request nobody will ever answer.
     */
    private val onAutoApproved: ((Request, String) -> Unit)? = null,
) {

    private val appContext = context.applicationContext
    private val requests = RequestStore(appContext)
    private val grants = GrantStore(appContext)
    private val policyStore = LocalPolicyStore(appContext)
    private val notifier = RequestNotifier(appContext)
    private val autoRules = AutoApprovalStore(appContext)

    fun all(now: Instant = Instant.now()): List<Request> = requests.all(now)

    fun pending(now: Instant = Instant.now()): List<Request> = requests.pending(now)

    /** Whether asking is available on this phone at all. */
    fun isAvailable(): Boolean = FeatureFlags(appContext).isEnabled(Feature.REQUESTS)

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
        // Checked here rather than only in the UI: a notification action or a
        // screen left open from before the feature was put away would otherwise
        // still get a request through.
        if (!isAvailable()) return null
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
        // Always recorded as an ask, even when the answer is instant. Two rows
        // rather than one, or the shared history shows time granted with nothing
        // asking for it.
        record(AuditActor.CHILD, AuditAction.REQUEST_RAISED, request)

        autoApprove(request, now)?.let { return it }

        notifier.notifyRequestReceived(request)
        onRaised?.invoke(request)
        return request
    }

    /**
     * Lets a standing rule answer, if one will.
     *
     * Note what does not happen when none will: nothing. The request stays open
     * and goes to a parent exactly as before. There is deliberately no automatic
     * *denial* — three of those in an evening would spend the child's
     * three-denials-a-day allowance and lock them out of asking a person, which
     * would mean the machine had taken away the right to ask.
     */
    private fun autoApprove(request: Request, now: Instant): Request? {
        // Put away separately from requests themselves, so a family can keep the
        // asking and drop the automation.
        if (!FeatureFlags(appContext).isEnabled(Feature.AUTO_APPROVE)) return null

        val decision = AutoApprovalRules.decide(
            request = request,
            rules = autoRules.rules,
            history = requests.all(now),
            dayStart = startOfToday(now),
            weekStart = startOfWeek(now),
        )
        val rule = (decision as? AutoDecision.Approve)?.rule ?: return null

        val (approved, grant) = RequestRules.autoApprove(
            request = request,
            now = now,
            ruleId = rule.id,
            grantExpiresAt = endOfToday(now),
            grantId = GrantId(UUID.randomUUID().toString()),
        )
        // The grant first, as with a parent's approval: a crash between the two
        // leaves spare time and a pending ask, which is recoverable, where the
        // other order leaves an approval that granted nothing.
        grants.add(grant)
        requests.save(approved)
        autoRules.recordFired()

        Audit.record(
            context = appContext,
            actor = AuditActor.SYSTEM,
            action = AuditAction.REQUEST_AUTO_APPROVED,
            subject = TargetLabels.describe(appContext, approved.target),
            after = Audit.minutes(approved.amount.inWholeMinutes.toInt()),
        )
        notifier.notifyDecision(approved)
        onAutoApproved?.invoke(approved, rule.name)
        return approved
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
        record(AuditActor.PARENT, AuditAction.REQUEST_APPROVED, approved)
        notifier.notifyDecision(approved)
        onDecided?.invoke(approved)
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
        record(AuditActor.PARENT, AuditAction.REQUEST_DENIED, denied)
        notifier.notifyDecision(denied)
        onDecided?.invoke(denied)
        return true
    }

    fun withdraw(id: RequestId, now: Instant = Instant.now()): Boolean {
        val request = requests.find(id, now)?.takeIf { it.state.isOpen } ?: return false
        requests.save(RequestRules.withdraw(request, now))
        record(AuditActor.CHILD, AuditAction.REQUEST_WITHDRAWN, request)
        return true
    }

    /**
     * Tells the child about any request that has lapsed unanswered since the
     * last call, and records it in the shared history.
     *
     * [RequestStore.all] computes expiry lazily, on whichever read notices the
     * deadline has passed — deliberately: a lapse is not an event anybody
     * caused, so there is no single moment to hook a notification off of
     * directly. This is the one caller for whom "noticed" is the point, so it
     * keeps its own small record of which lapsed requests it has already
     * announced, and only acts on ones that are not in it yet. Meant to be
     * called periodically (the enforcement tick) rather than from a screen —
     * a parent or child opening [RequestsActivity] and merely looking at an
     * expired request must not, by itself, count as "announced".
     */
    fun notifyLapsedRequests(now: Instant = Instant.now()) {
        val expired = requests.all(now).filter { it.state == RequestState.EXPIRED }
        if (expired.isEmpty()) return

        val announced = ExpiredRequestLog(appContext)
        val newlyLapsed = expired.filterNot { announced.contains(it.id) }
        if (newlyLapsed.isEmpty()) return

        newlyLapsed.forEach { request ->
            record(AuditActor.SYSTEM, AuditAction.REQUEST_EXPIRED, request)
            notifier.notifyExpired(request)
        }
        announced.markAnnounced(newlyLapsed.map { it.id })
    }

    /**
     * Writes the request into the shared history.
     *
     * Both sides read the same list, which is the point: a denial the child cannot
     * find a record of is a denial they will remember differently from the parent.
     */
    private fun record(actor: AuditActor, action: AuditAction, request: Request) {
        Audit.record(
            context = appContext,
            actor = actor,
            action = action,
            subject = TargetLabels.describe(appContext, request.target),
            after = Audit.minutes(request.amount.inWholeMinutes.toInt()),
        )
    }

    /** The amounts the compose screen offers, mirroring the bonus-time options. */
    fun suggestedAmounts(): List<Duration> =
        appContext.resources
            .getIntArray(dev.schrammel.protego_parentis.R.array.bonus_minute_options)
            .map { it.minutes }

    private fun startOfToday(now: Instant): Instant {
        val policy = policyStore.load()
        val day = PolicyClock.policyDay(now, policy.timeZone, policy.dayBoundaryHour)
        return PolicyClock.dayStart(day, policy.timeZone, policy.dayBoundaryHour)
    }

    /**
     * The start of the policy week, for counting a weekly allowance.
     *
     * Seven policy days back rather than "since Monday": a rolling week cannot be
     * reset by waiting for a boundary, and a child who has learned that Sunday
     * evening is free would have found the one gap worth finding.
     */
    private fun startOfWeek(now: Instant): Instant =
        startOfToday(now).minusMillis(WEEK_DAYS * MILLIS_PER_DAY)

    private fun endOfToday(now: Instant): Instant {
        val policy = policyStore.load()
        val day = PolicyClock.policyDay(now, policy.timeZone, policy.dayBoundaryHour)
        return PolicyClock.dayEnd(day, policy.timeZone, policy.dayBoundaryHour)
    }

    private companion object {
        /** Until the server exists, the answer comes from whoever holds the PIN. */
        const val DECIDED_BY_PARENT = "parent"

        const val WEEK_DAYS = 7L
        const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
    }
}
