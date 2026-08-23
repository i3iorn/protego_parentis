package dev.schrammel.dementor_watch.policy.reconcile

import dev.schrammel.dementor_watch.model.BudgetLevel
import dev.schrammel.dementor_watch.model.BudgetStatus
import dev.schrammel.dementor_watch.model.Enforcement
import dev.schrammel.dementor_watch.model.InstallPolicy
import dev.schrammel.dementor_watch.model.PackageName
import dev.schrammel.dementor_watch.model.Reason
import dev.schrammel.dementor_watch.policy.DIALER
import dev.schrammel.dementor_watch.policy.GAME
import dev.schrammel.dementor_watch.policy.HOMEWORK
import dev.schrammel.dementor_watch.policy.YOUTUBE
import dev.schrammel.dementor_watch.policy.policy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration

private val SELF = PackageName("dev.schrammel.dementor_watch")

/** A stand-in for `DevicePolicyManager` that records what it was asked to do. */
internal class FakeDevicePolicyPort(
    var deviceOwner: Boolean = true,
    private val rejects: Set<PackageName> = emptySet(),
) : DevicePolicyPort {

    val suspendedPackages = mutableSetOf<PackageName>()
    val hiddenPackages = mutableSetOf<PackageName>()
    val restrictions = mutableSetOf<String>()
    val uninstallBlocked = mutableSetOf<PackageName>()
    var suspendCallCount = 0
        private set

    override fun isDeviceOwner(): Boolean = deviceOwner

    override fun currentlySuspended(): Set<PackageName> = suspendedPackages.toSet()

    override fun currentlyHidden(): Set<PackageName> = hiddenPackages.toSet()

    override fun setSuspended(packages: Set<PackageName>, suspended: Boolean): Set<PackageName> {
        suspendCallCount++
        val failures = packages intersect rejects
        val applied = packages - failures
        if (suspended) suspendedPackages += applied else suspendedPackages -= applied
        return failures
    }

    override fun setHidden(pkg: PackageName, hidden: Boolean): Boolean {
        if (pkg in rejects) return false
        if (hidden) hiddenPackages += pkg else hiddenPackages -= pkg
        return true
    }

    override fun currentUserRestrictions(): Set<String> = restrictions.toSet()

    override fun addUserRestriction(key: String) {
        restrictions += key
    }

    override fun clearUserRestriction(key: String) {
        restrictions -= key
    }

    override fun setUninstallBlocked(pkg: PackageName, blocked: Boolean) {
        if (blocked) uninstallBlocked += pkg else uninstallBlocked -= pkg
    }

    override fun clearDeviceOwner() {
        deviceOwner = false
    }
}

class PolicyReconcilerTest {

    private fun enforcement(
        suspend: Set<PackageName> = emptySet(),
        hide: Set<PackageName> = emptySet(),
        version: Long = 1L,
        installsBlocked: Boolean = false,
    ) = Enforcement(
        policyVersion = version,
        suspend = suspend.associateWith { Reason.ManuallyBlocked },
        hide = hide,
        deferred = emptyMap(),
        deviceBudget = BudgetStatus(
            allowed = Duration.INFINITE,
            used = Duration.ZERO,
            bonus = Duration.ZERO,
            remaining = Duration.INFINITE,
            level = BudgetLevel.NORMAL,
        ),
        appBudgets = emptyMap(),
        categoryBudgets = emptyMap(),
        activeSchedules = emptyList(),
        installsBlocked = installsBlocked,
        nextChangeAt = null,
    )

    // ---------- guard ----------

    @Test
    fun `without device ownership nothing is applied`() {
        val port = FakeDevicePolicyPort(deviceOwner = false)
        val result = PolicyReconciler(port, SELF).reconcile(policy(), enforcement(suspend = setOf(GAME)))

        assertFalse(result.applied)
        assertEquals("not device owner", result.skippedReason)
        assertEquals(emptySet<PackageName>(), port.suspendedPackages)
        assertEquals(0, port.suspendCallCount)
    }

    // ---------- suspensions ----------

    @Test
    fun `desired packages are suspended`() {
        val port = FakeDevicePolicyPort()
        val result = PolicyReconciler(port, SELF)
            .reconcile(policy(), enforcement(suspend = setOf(GAME, YOUTUBE)))

        assertTrue(result.applied)
        assertEquals(setOf(GAME, YOUTUBE), port.suspendedPackages)
        assertEquals(setOf(GAME, YOUTUBE), result.suspended)
    }

    @Test
    fun `packages no longer wanted are unsuspended`() {
        val port = FakeDevicePolicyPort()
        port.suspendedPackages += setOf(GAME, YOUTUBE)

        val result = PolicyReconciler(port, SELF).reconcile(policy(), enforcement(suspend = setOf(GAME)))

        assertEquals(setOf(GAME), port.suspendedPackages)
        assertEquals(setOf(YOUTUBE), result.unsuspended)
        assertEquals(emptySet<PackageName>(), result.suspended)
    }

    @Test
    fun `a second pass over unchanged state does nothing`() {
        val port = FakeDevicePolicyPort()
        val reconciler = PolicyReconciler(port, SELF)
        val desired = enforcement(suspend = setOf(GAME))

        reconciler.reconcile(policy(), desired)
        val callsAfterFirst = port.suspendCallCount
        val second = reconciler.reconcile(policy(), desired)

        assertTrue("reconciliation must be idempotent", second.isNoOp)
        assertEquals(callsAfterFirst, port.suspendCallCount)
    }

    @Test
    fun `packages the system refuses are reported as failures`() {
        val port = FakeDevicePolicyPort(rejects = setOf(YOUTUBE))
        val result = PolicyReconciler(port, SELF)
            .reconcile(policy(), enforcement(suspend = setOf(GAME, YOUTUBE)))

        assertEquals(setOf(YOUTUBE), result.failed)
        assertEquals(setOf(GAME), result.suspended)
        assertEquals(setOf(GAME), port.suspendedPackages)
    }

    // ---------- safety invariant ----------

    @Test
    fun `a safety-exempt package is refused even if enforcement asks for it`() {
        val port = FakeDevicePolicyPort()
        val result = PolicyReconciler(port, SELF)
            .reconcile(policy(), enforcement(suspend = setOf(DIALER, GAME)))

        assertEquals(setOf(GAME), port.suspendedPackages)
        assertEquals(1, result.invariantViolations.size)
        assertTrue(result.invariantViolations.single().contains(DIALER.value))
    }

    @Test
    fun `the app refuses to suspend itself`() {
        val port = FakeDevicePolicyPort()
        val result = PolicyReconciler(port, SELF).reconcile(policy(), enforcement(suspend = setOf(SELF)))

        assertEquals(emptySet<PackageName>(), port.suspendedPackages)
        assertEquals(1, result.invariantViolations.size)
    }

    @Test
    fun `normal operation reports no violations`() {
        val port = FakeDevicePolicyPort()
        val result = PolicyReconciler(port, SELF)
            .reconcile(policy(), enforcement(suspend = setOf(GAME, YOUTUBE, HOMEWORK)))

        assertEquals(emptyList<String>(), result.invariantViolations)
    }

    // ---------- hidden apps ----------

    @Test
    fun `hidden apps are hidden and later restored`() {
        val port = FakeDevicePolicyPort()
        val reconciler = PolicyReconciler(port, SELF)

        val hiddenPass = reconciler.reconcile(policy(), enforcement(hide = setOf(GAME)))
        assertEquals(setOf(GAME), port.hiddenPackages)
        assertEquals(setOf(GAME), hiddenPass.hidden)

        val restoredPass = reconciler.reconcile(policy(), enforcement())
        assertEquals(emptySet<PackageName>(), port.hiddenPackages)
        assertEquals(setOf(GAME), restoredPass.unhidden)
    }

    // ---------- tamper restrictions ----------

    @Test
    fun `baseline restrictions are applied on the first pass`() {
        val port = FakeDevicePolicyPort()
        val result = PolicyReconciler(port, SELF).reconcile(policy(), enforcement())

        assertEquals(TamperRestrictions.BASELINE, port.restrictions)
        assertEquals(TamperRestrictions.BASELINE, result.restrictionsAdded)
    }

    @Test
    fun `the clock restriction is part of the baseline`() {
        // Without it, changing the system clock defeats every schedule and budget.
        assertTrue(TamperRestrictions.NO_CONFIG_DATE_TIME in TamperRestrictions.BASELINE)
        assertEquals("no_config_date_time", TamperRestrictions.NO_CONFIG_DATE_TIME)
    }

    /**
     * The restriction that stops the app's own data being cleared from Settings.
     *
     * Without it the whole product is four taps from gone — PIN, rules, shared
     * history and the record of which apps were already installed — leaving an app
     * that still looks installed and enforces nothing.
     */
    @Test
    fun `lockdown stops the app being cleared or force-stopped`() {
        val port = FakeDevicePolicyPort()
        PolicyReconciler(port, SELF)
            .reconcile(policy().copy(tamperProtection = true), enforcement())

        assertTrue(TamperRestrictions.NO_CONTROL_APPS in port.restrictions)
    }

    /** It is part of the lockdown switch, not of being provisioned. */
    @Test
    fun `without lockdown the app can still be cleared`() {
        val port = FakeDevicePolicyPort()
        PolicyReconciler(port, SELF)
            .reconcile(policy().copy(tamperProtection = false), enforcement())

        assertFalse(TamperRestrictions.NO_CONTROL_APPS in port.restrictions)
    }

    @Test
    fun `blocked installs add the install restriction`() {
        val port = FakeDevicePolicyPort()
        PolicyReconciler(port, SELF).reconcile(
            policy().copy(installPolicy = InstallPolicy.GATED),
            enforcement(installsBlocked = true),
        )

        assertTrue(TamperRestrictions.NO_INSTALL_APPS in port.restrictions)
    }

    @Test
    fun `un-blocking installs clears the restriction`() {
        val port = FakeDevicePolicyPort()
        val reconciler = PolicyReconciler(port, SELF)

        reconciler.reconcile(policy(), enforcement(installsBlocked = true))
        val result = reconciler.reconcile(policy(), enforcement(installsBlocked = false))

        assertFalse(TamperRestrictions.NO_INSTALL_APPS in port.restrictions)
        assertEquals(setOf(TamperRestrictions.NO_INSTALL_APPS), result.restrictionsCleared)
    }

    /**
     * The gate is the engine\'s decision, not this class\'s. A gated policy whose
     * installation window is open must leave installs alone, and the reconciler
     * must not second-guess that by reading the policy itself.
     */
    @Test
    fun `a gated policy does not block installs on its own`() {
        val port = FakeDevicePolicyPort()
        PolicyReconciler(port, SELF).reconcile(
            policy().copy(installPolicy = InstallPolicy.GATED),
            enforcement(installsBlocked = false),
        )

        assertFalse(TamperRestrictions.NO_INSTALL_APPS in port.restrictions)
    }

    @Test
    fun `tamper protection off applies no baseline restrictions`() {
        val port = FakeDevicePolicyPort()
        val result = PolicyReconciler(port, SELF)
            .reconcile(policy().copy(tamperProtection = false), enforcement())

        assertEquals(emptySet<String>(), port.restrictions)
        assertEquals(emptySet<String>(), result.restrictionsAdded)
    }

    @Test
    fun `tamper protection off leaves the app removable`() {
        val port = FakeDevicePolicyPort()
        PolicyReconciler(port, SELF)
            .reconcile(policy().copy(tamperProtection = false), enforcement())

        assertEquals(emptySet<PackageName>(), port.uninstallBlocked)
    }

    @Test
    fun `turning tamper protection off clears restrictions already applied`() {
        val port = FakeDevicePolicyPort()
        val reconciler = PolicyReconciler(port, SELF)

        reconciler.reconcile(policy(), enforcement())
        val result = reconciler.reconcile(policy().copy(tamperProtection = false), enforcement())

        assertEquals(emptySet<String>(), port.restrictions)
        assertEquals(TamperRestrictions.BASELINE, result.restrictionsCleared)
        assertEquals(emptySet<PackageName>(), port.uninstallBlocked)
    }

    @Test
    fun `limits are still enforced with tamper protection off`() {
        val port = FakeDevicePolicyPort()
        PolicyReconciler(port, SELF).reconcile(
            policy().copy(tamperProtection = false),
            enforcement(suspend = setOf(GAME)),
        )

        assertEquals(setOf(GAME), port.suspendedPackages)
    }

    @Test
    fun `restrictions set by someone else are left alone`() {
        val port = FakeDevicePolicyPort()
        port.restrictions += "no_outgoing_beam"

        val result = PolicyReconciler(port, SELF).reconcile(policy(), enforcement())

        assertTrue("no_outgoing_beam" in port.restrictions)
        assertEquals(emptySet<String>(), result.restrictionsCleared)
    }

    // ---------- self protection ----------

    @Test
    fun `the app blocks its own uninstall`() {
        val port = FakeDevicePolicyPort()
        PolicyReconciler(port, SELF).reconcile(policy(), enforcement())

        assertEquals(setOf(SELF), port.uninstallBlocked)
    }

    @Test
    fun `the result carries the policy version it applied`() {
        val port = FakeDevicePolicyPort()
        val result = PolicyReconciler(port, SELF).reconcile(policy(), enforcement(version = 77L))

        assertEquals(77L, result.policyVersion)
    }
}
