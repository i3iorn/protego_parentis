package dev.schrammel.protego_parentis.policy.reconcile

import dev.schrammel.protego_parentis.model.BudgetLevel
import dev.schrammel.protego_parentis.model.BudgetStatus
import dev.schrammel.protego_parentis.model.Enforcement
import dev.schrammel.protego_parentis.model.InstallPolicy
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.RestrictionValue
import dev.schrammel.protego_parentis.model.Reason
import dev.schrammel.protego_parentis.policy.DIALER
import dev.schrammel.protego_parentis.policy.GAME
import dev.schrammel.protego_parentis.policy.HOMEWORK
import dev.schrammel.protego_parentis.policy.YOUTUBE
import dev.schrammel.protego_parentis.policy.policy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration

private val SELF = PackageName("dev.schrammel.protego_parentis")

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

    /** Null on both halves, the way the platform reports "never set". */
    var supportMessages = SupportMessages(short = null, long = null)
        private set
    var supportMessageWrites = 0
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

    var privateDnsHost: String? = null

    /** Set to refuse the next change, standing in for a host that has no DoT. */
    var refuseDns: Boolean = false

    val appRestrictions: MutableMap<PackageName, Map<String, RestrictionValue>> = mutableMapOf()

    var locks = 0
    var secureLockScreen = true
    var networkLogging = false
    var alwaysOnVpn: String? = null

    override fun lockScreen() {
        locks++
    }

    override fun hasSecureLockScreen(): Boolean = secureLockScreen

    override fun isNetworkLoggingEnabled(): Boolean = networkLogging

    override fun setNetworkLoggingEnabled(enabled: Boolean): Boolean {
        networkLogging = enabled
        return true
    }

    override fun currentAlwaysOnVpn(): String? = alwaysOnVpn

    override fun setAlwaysOnVpn(packageName: String?): Boolean {
        alwaysOnVpn = packageName
        return true
    }

    override fun currentPrivateDnsHost(): String? = privateDnsHost

    override fun setPrivateDnsHost(host: String?): Boolean {
        if (refuseDns) return false
        privateDnsHost = host
        return true
    }

    override fun setApplicationRestrictions(
        pkg: PackageName,
        restrictions: Map<String, RestrictionValue>,
    ) {
        if (restrictions.isEmpty()) appRestrictions -= pkg else appRestrictions[pkg] = restrictions
    }

    override fun currentSupportMessages(): SupportMessages = supportMessages

    override fun setSupportMessages(messages: SupportMessages) {
        supportMessages = messages
        supportMessageWrites++
    }

    override fun clearDeviceOwner() {
        deviceOwner = false
    }
}

class PolicyReconcilerTest {

    private val CHROME = PackageName("com.android.chrome")

    private fun enforcement(
        suspend: Set<PackageName> = emptySet(),
        hide: Set<PackageName> = emptySet(),
        version: Long = 1L,
        installsBlocked: Boolean = false,
        dnsHost: String? = null,
        appRestrictions: Map<PackageName, Map<String, RestrictionValue>> = emptyMap(),
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
        dnsHost = dnsHost,
        appRestrictions = appRestrictions,
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

    /**
     * Not part of the lockdown switch, deliberately, and this test used to assert
     * the opposite.
     *
     * The reasoning it was changed on: everything in LOCKDOWN takes something
     * away from the person holding the phone — no factory reset, no safe mode, no
     * clock — and a family may reasonably decline all of it. This one takes
     * nothing away from them. It only stops the app being emptied, and an emptied
     * app is not merely unenforcing: the roster goes with the rules, a phone with
     * no trusted parents is the bootstrap state, and the managed user is handed
     * the founding-parent path on a device that is still device owner.
     *
     * So turning lockdown off is a family saying "do not restrict this phone",
     * which is theirs to say. It is not them saying "let whoever holds it become
     * a parent", which is what the old behaviour delivered.
     */
    @Test
    fun `the app cannot be cleared even with lockdown off`() {
        val port = FakeDevicePolicyPort()
        PolicyReconciler(port, SELF)
            .reconcile(policy().copy(tamperProtection = false), enforcement())

        assertTrue(TamperRestrictions.NO_CONTROL_APPS in port.restrictions)
    }

    /** The rest of the lockdown does come off, which is the point of the switch. */
    @Test
    fun `turning lockdown off lifts everything that restricts the phone`() {
        val port = FakeDevicePolicyPort()
        PolicyReconciler(port, SELF)
            .reconcile(policy().copy(tamperProtection = false), enforcement())

        assertEquals(TamperRestrictions.ALWAYS, port.restrictions)
        assertTrue(TamperRestrictions.LOCKDOWN.none { it in port.restrictions })
    }

    // ---------- web filtering ----------

    @Test
    fun `points the device at the filtering resolver`() {
        val port = FakeDevicePolicyPort()
        val result = PolicyReconciler(port, SELF)
            .reconcile(policy(), enforcement(dnsHost = "family.cloudflare-dns.com"))

        assertEquals("family.cloudflare-dns.com", port.privateDnsHost)
        assertTrue(result.dnsChanged)
    }

    @Test
    fun `leaves the resolver alone when it already matches`() {
        val port = FakeDevicePolicyPort().apply { privateDnsHost = "family.cloudflare-dns.com" }

        val result = PolicyReconciler(port, SELF)
            .reconcile(policy(), enforcement(dnsHost = "family.cloudflare-dns.com"))

        assertFalse(result.dnsChanged)
    }

    @Test
    fun `turning filtering off releases the resolver`() {
        val port = FakeDevicePolicyPort().apply { privateDnsHost = "family.cloudflare-dns.com" }

        PolicyReconciler(port, SELF).reconcile(policy(), enforcement())

        assertNull(port.privateDnsHost)
    }

    /**
     * A host that does not answer DNS-over-TLS is refused by Android. Failing
     * quietly would leave a family believing the phone was filtered when it never
     * was, which is worse than not offering the feature.
     */
    @Test
    fun `a resolver that cannot be set is reported as a violation`() {
        val port = FakeDevicePolicyPort().apply { refuseDns = true }

        val result = PolicyReconciler(port, SELF)
            .reconcile(policy(), enforcement(dnsHost = "not.a.resolver"))

        assertFalse(result.dnsChanged)
        assertTrue(result.invariantViolations.any { it.contains("not.a.resolver") })
    }

    /** A filter that can be switched off in Settings is decoration. */
    @Test
    fun `filtering locks the DNS and VPN settings without lockdown`() {
        val port = FakeDevicePolicyPort()
        PolicyReconciler(port, SELF).reconcile(
            policy().copy(tamperProtection = false),
            enforcement(dnsHost = "family.cloudflare-dns.com"),
        )

        assertTrue(TamperRestrictions.NO_CONFIG_PRIVATE_DNS in port.restrictions)
        assertTrue(TamperRestrictions.NO_CONFIG_VPN in port.restrictions)
        // Not the rest of the lockdown, which the family did not ask for.
        assertFalse(TamperRestrictions.NO_FACTORY_RESET in port.restrictions)
    }

    @Test
    fun `pushes managed configuration to the browser`() {
        val port = FakeDevicePolicyPort()
        val config = mapOf<String, RestrictionValue>("ForceGoogleSafeSearch" to RestrictionValue.Flag(true))

        PolicyReconciler(port, SELF).reconcile(
            policy(),
            enforcement(appRestrictions = mapOf(CHROME to config)),
        )

        assertEquals(config, port.appRestrictions[CHROME])
    }

    /** Yesterday's blocklist must not outlive the rule that set it. */
    @Test
    fun `clears configuration for a package it no longer manages`() {
        val port = FakeDevicePolicyPort()
        val reconciler = PolicyReconciler(port, SELF)
        val config = mapOf<String, RestrictionValue>("ForceGoogleSafeSearch" to RestrictionValue.Flag(true))

        reconciler.reconcile(policy(), enforcement(appRestrictions = mapOf(CHROME to config)))
        reconciler.reconcile(policy(), enforcement())

        assertNull(port.appRestrictions[CHROME])
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

    /**
     * Off means none of the lockdown, not none of the protection.
     *
     * The set left behind is exactly [TamperRestrictions.ALWAYS] — the one
     * restriction that stops this app's own data being cleared. It is not part
     * of the switch, because clearing the data does not merely stop enforcement:
     * it empties the roster, and a phone with no trusted parents lets whoever is
     * holding it enrol themselves as the founding parent.
     */
    @Test
    fun `tamper protection off applies nothing that restricts the phone`() {
        val port = FakeDevicePolicyPort()
        val result = PolicyReconciler(port, SELF)
            .reconcile(policy().copy(tamperProtection = false), enforcement())

        assertEquals(TamperRestrictions.ALWAYS, port.restrictions)
        assertEquals(TamperRestrictions.ALWAYS, result.restrictionsAdded)
        assertTrue(TamperRestrictions.LOCKDOWN.none { it in port.restrictions })
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

        // The lockdown comes off; the self-protection does not, so it is not in
        // the cleared set either.
        assertEquals(TamperRestrictions.ALWAYS, port.restrictions)
        assertEquals(TamperRestrictions.LOCKDOWN, result.restrictionsCleared)
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
