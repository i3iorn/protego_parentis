package dev.schrammel.protego_parentis.policy.reconcile

import dev.schrammel.protego_parentis.model.PackageName
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ReconcileResult.isNoOp] feeds the "cheap to log" shortcut its own doc comment
 * invites — so it has to be true only when nothing worth logging happened, not
 * merely when the package-suspension fields are empty.
 */
class ReconcileResultTest {

    private val base = ReconcileResult(applied = true, policyVersion = 1L)

    @Test
    fun `a pass that changed nothing is a no-op`() {
        assertTrue(base.isNoOp)
    }

    @Test
    fun `a skipped pass is never a no-op`() {
        assertFalse(ReconcileResult.skipped("no policy yet", policyVersion = 1L).isNoOp)
    }

    @Test
    fun `suspending or hiding a package is not a no-op`() {
        assertFalse(base.copy(suspended = setOf(PackageName("a"))).isNoOp)
        assertFalse(base.copy(unsuspended = setOf(PackageName("a"))).isNoOp)
        assertFalse(base.copy(hidden = setOf(PackageName("a"))).isNoOp)
        assertFalse(base.copy(unhidden = setOf(PackageName("a"))).isNoOp)
        assertFalse(base.copy(restrictionsAdded = setOf("r")).isNoOp)
        assertFalse(base.copy(restrictionsCleared = setOf("r")).isNoOp)
    }

    @Test
    fun `a DNS or VPN change is not a no-op even with nothing else to show`() {
        assertFalse(base.copy(dnsChanged = true).isNoOp)
        assertFalse(base.copy(vpnChanged = true).isNoOp)
    }

    @Test
    fun `a failed package or an invariant violation is not a no-op`() {
        assertFalse(base.copy(failed = setOf(PackageName("a"))).isNoOp)
        assertFalse(base.copy(invariantViolations = listOf("never restrict the safety allowlist")).isNoOp)
    }
}
