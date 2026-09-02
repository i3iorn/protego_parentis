package dev.schrammel.protego_parentis.model

import java.time.LocalDate
import kotlin.time.Duration

/**
 * Foreground time consumed so far during one policy day.
 *
 * [deviceTotal] is tracked separately from the sum of [byPackage] because
 * safety-exempt apps do not count against the device budget, so the two
 * legitimately disagree.
 */
data class UsageSnapshot(
    val policyDay: LocalDate,
    val deviceTotal: Duration,
    val byPackage: Map<PackageName, Duration> = emptyMap(),
) {
    fun forPackage(pkg: PackageName): Duration = byPackage[pkg] ?: Duration.ZERO

    companion object {
        fun empty(day: LocalDate) = UsageSnapshot(day, Duration.ZERO)
    }
}

/**
 * What the device is doing right now.
 *
 * [navigatingPackage] defers that one app's suspension rather than cancelling it
 * — see the "never a hard cutoff" principle. The reconciler re-evaluates when it
 * clears.
 */
data class DeviceState(
    val foregroundPackage: PackageName? = null,
    /**
     * The app currently running a navigation session, if any.
     *
     * Bedtime arriving while somebody is walking or driving home must not take
     * the map with it. Only this app is spared, and only while the session is
     * live: a blanket lift would make "start navigating" a way to unlock the
     * whole phone.
     *
     * Calls are deliberately not modelled here. The dialer and messaging app are
     * safety-exempt already, so they are never suspended in the first place, and
     * reading call state would cost a `READ_PHONE_STATE` declaration to protect
     * something that is not exposed.
     */
    val navigatingPackage: PackageName? = null,
    /**
     * An emergency the child raised (LOC-3).
     *
     * Lifts every restriction outright, rather than deferring it the way a call
     * does. The difference matters: a deferred suspension is still coming, and a
     * child who has pressed the SOS button should not find the phone locking down
     * again the moment it thinks the emergency is over.
     *
     * Device state rather than policy, because it is a fact about the phone right
     * now and not a rule the family wrote.
     */
    val emergency: Boolean = false,
) {
    /** The one package whose suspension is held back right now, if any. */
    val suspensionDeferredFor: PackageName? get() = navigatingPackage

    companion object {
        val IDLE = DeviceState()
    }
}
