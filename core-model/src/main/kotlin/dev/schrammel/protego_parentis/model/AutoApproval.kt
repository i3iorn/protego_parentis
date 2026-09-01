package dev.schrammel.protego_parentis.model

import java.time.Instant
import kotlin.time.Duration

/**
 * A standing answer to a kind of request (RQ-1).
 *
 * The negotiation loop is the product's central idea, and its failure mode is the
 * parent becoming a bottleneck: a child who has to wait two hours for fifteen
 * minutes stops asking and starts working around. A standing rule keeps the loop
 * — the request is still made, still recorded, still visible to both sides — while
 * removing the wait for the cases a parent already knows their answer to.
 *
 * Deliberately a *cap*, not a blanket yes. Every field is an upper bound, and a
 * request that exceeds any of them falls through to a person.
 */
data class AutoApproval(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    /** What it will answer for. Null means any target. */
    val target: GrantTarget? = null,
    /** The most it will grant in one go. A larger ask goes to a parent. */
    val maxAmount: Duration,
    /** How many it will answer inside one [window]. */
    val maxPerWindow: Int,
    val window: AutoApprovalWindow = AutoApprovalWindow.WEEK,
) {
    init {
        require(id.isNotBlank()) { "an auto-approval needs an id" }
        require(name.isNotBlank()) { "an auto-approval needs a name to show in the history" }
        require(maxAmount.isPositive()) { "an auto-approval that grants nothing is a denial" }
        require(maxPerWindow > 0) { "an auto-approval that never fires is a denial" }
    }

    /** Whether this rule is willing to answer for [target] at all. */
    fun covers(candidate: GrantTarget): Boolean = target == null || target == candidate
}

/** How far back [AutoApproval.maxPerWindow] counts. */
enum class AutoApprovalWindow {
    /** Since the last policy day boundary. */
    DAY,

    /** The seven policy days ending today. */
    WEEK,
}
