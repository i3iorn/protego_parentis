package dev.schrammel.protego_parentis.model

import java.time.Instant
import kotlin.time.Duration

/** What a bonus-time grant applies to. */
sealed interface GrantTarget {
    /** Extends the device-wide budget. */
    data object Device : GrantTarget

    /** Extends one app's budget only. */
    data class App(val pkg: PackageName) : GrantTarget

    /** Extends one category's budget — "another 20 minutes of games". */
    data class Category(val id: CategoryId) : GrantTarget
}

/**
 * Extra time granted by a parent, expiring at the next day boundary.
 *
 * Grants deliberately never modify the underlying [WeeklyBudget]: reports need to
 * distinguish a week that went well from a week that was topped up.
 */
data class Grant(
    val id: GrantId,
    val target: GrantTarget,
    val amount: Duration,
    val grantedAt: Instant,
    val expiresAt: Instant,
    val grantedBy: String,
    val sourceRequestId: String? = null,
) {
    init {
        require(amount.isPositive()) { "grant amount must be positive" }
        require(expiresAt.isAfter(grantedAt)) { "grant expires before it is issued" }
    }

    fun isActiveAt(now: Instant): Boolean = now.isBefore(expiresAt) && !now.isBefore(grantedAt)
}
