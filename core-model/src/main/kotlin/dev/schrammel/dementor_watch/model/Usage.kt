package dev.schrammel.dementor_watch.model

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
 * [callInProgress] and [navigationInProgress] defer suspension rather than
 * cancelling it — see the "never a hard cutoff" principle. The reconciler
 * re-evaluates when they clear.
 */
data class DeviceState(
    val foregroundPackage: PackageName? = null,
    val callInProgress: Boolean = false,
    val navigationInProgress: Boolean = false,
) {
    val suspensionDeferred: Boolean get() = callInProgress || navigationInProgress

    companion object {
        val IDLE = DeviceState()
    }
}
