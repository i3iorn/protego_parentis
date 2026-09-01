package dev.schrammel.protego_parentis.policy.reconcile

import dev.schrammel.protego_parentis.model.PackageName

/**
 * What one reconciliation pass actually did.
 *
 * Fed straight into the shared audit log (TR-3) and the heartbeat (TR-2), so it
 * records the outcome rather than just the intent — a package the OS refused to
 * suspend shows up in [failed] rather than being silently assumed applied.
 */
data class ReconcileResult(
    val applied: Boolean,
    val policyVersion: Long,
    val suspended: Set<PackageName> = emptySet(),
    val unsuspended: Set<PackageName> = emptySet(),
    val hidden: Set<PackageName> = emptySet(),
    val unhidden: Set<PackageName> = emptySet(),
    val failed: Set<PackageName> = emptySet(),
    val restrictionsAdded: Set<String> = emptySet(),
    val restrictionsCleared: Set<String> = emptySet(),
    /** Whether this pass changed where the device sends its DNS queries (WF-1). */
    val dnsChanged: Boolean = false,
    /** Whether this pass started or stopped the app-blocking tunnel (WF-4). */
    val vpnChanged: Boolean = false,
    /**
     * Attempts to restrict something that must never be restricted. Always empty
     * in normal operation; a non-empty list means a bug upstream and should raise
     * an alert rather than be swallowed.
     */
    val invariantViolations: List<String> = emptyList(),
    /** Why nothing was applied, when [applied] is false. */
    val skippedReason: String? = null,
) {
    /** True when the pass changed nothing — the common case, and cheap to log. */
    val isNoOp: Boolean
        get() = applied &&
            suspended.isEmpty() && unsuspended.isEmpty() &&
            hidden.isEmpty() && unhidden.isEmpty() &&
            restrictionsAdded.isEmpty() && restrictionsCleared.isEmpty()

    companion object {
        fun skipped(reason: String, policyVersion: Long) =
            ReconcileResult(applied = false, policyVersion = policyVersion, skippedReason = reason)
    }
}
