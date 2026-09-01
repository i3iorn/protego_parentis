package dev.schrammel.protego_parentis.policy

import dev.schrammel.protego_parentis.model.BrowserPolicy
import dev.schrammel.protego_parentis.model.BrowserRestrictions
import dev.schrammel.protego_parentis.model.ChildId
import dev.schrammel.protego_parentis.model.DnsFilter
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.Policy
import dev.schrammel.protego_parentis.model.Reason
import dev.schrammel.protego_parentis.model.RestrictionValue
import dev.schrammel.protego_parentis.model.UsageSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** Device-wide DNS filtering and browser policy (WF-1, WF-2). */
class WebFilterTest {

    private val zone: ZoneId = ZoneId.of("Europe/Stockholm")
    private val now: Instant = Instant.parse("2026-08-24T14:00:00Z")

    private val chrome = PackageName(BrowserRestrictions.CHROME)
    private val firefox = PackageName("org.mozilla.firefox")
    private val notABrowser = PackageName("com.example.game")

    // ---------- WF-1 ----------

    @Test
    fun `no filter leaves the resolver alone`() {
        assertNull(evaluate(policy()).dnsHost)
    }

    @Test
    fun `a configured filter is carried to the reconciler`() {
        val filtered = policy(dns = DnsFilter("family.cloudflare-dns.com"))

        assertEquals("family.cloudflare-dns.com", evaluate(filtered).dnsHost)
    }

    /** Private DNS takes a hostname; a URL would be accepted and never work. */
    @Test
    fun `a filter refuses anything that is not a hostname`() {
        assertThrows(IllegalArgumentException::class.java) {
            DnsFilter("https://family.cloudflare-dns.com/dns-query")
        }
        assertThrows(IllegalArgumentException::class.java) { DnsFilter(" ") }
    }

    // ---------- WF-2 ----------

    @Test
    fun `no browser policy pushes no configuration`() {
        assertTrue(evaluate(policy()).appRestrictions.isEmpty())
    }

    @Test
    fun `safe search and restricted mode reach Chrome`() {
        val strict = policy(
            browser = BrowserPolicy(forceSafeSearch = true, restrictYouTube = true),
        )

        val chromeConfig = evaluate(strict).appRestrictions.getValue(chrome)

        assertEquals(
            RestrictionValue.Flag(true),
            chromeConfig[BrowserRestrictions.FORCE_SAFE_SEARCH],
        )
        assertEquals(
            RestrictionValue.Number(BrowserRestrictions.YOUTUBE_MODERATE),
            chromeConfig[BrowserRestrictions.FORCE_YOUTUBE_RESTRICT],
        )
    }

    @Test
    fun `blocking incognito uses Chrome's disabled value`() {
        val config = evaluate(policy(browser = BrowserPolicy(blockIncognito = true)))
            .appRestrictions
            .getValue(chrome)

        assertEquals(
            RestrictionValue.Number(BrowserRestrictions.INCOGNITO_DISABLED),
            config[BrowserRestrictions.INCOGNITO_AVAILABILITY],
        )
    }

    /**
     * An allowlist without a blocklist permits everything it names and everything
     * it does not, which reads on screen as a restriction and is not one.
     */
    @Test
    fun `an allowlist alone is not pushed`() {
        val config = BrowserRestrictions.forChrome(
            BrowserPolicy(allowedUrls = listOf("school.example")),
        )

        assertTrue(config.isEmpty())
    }

    @Test
    fun `an allowlist alongside a blocklist is pushed`() {
        val config = BrowserRestrictions.forChrome(
            BrowserPolicy(blockedUrls = listOf("*"), allowedUrls = listOf("school.example")),
        )

        assertEquals(RestrictionValue.Strings(listOf("*")), config[BrowserRestrictions.URL_BLOCKLIST])
        assertEquals(
            RestrictionValue.Strings(listOf("school.example")),
            config[BrowserRestrictions.URL_ALLOWLIST],
        )
    }

    /** The hole WF-2 leaves open unless it is closed deliberately. */
    @Test
    fun `an unmanageable browser is suspended when asked`() {
        val enforcement = evaluate(
            policy(browser = BrowserPolicy(suspendUnmanagedBrowsers = true)),
            installed = setOf(chrome, firefox, notABrowser),
            browsers = setOf(chrome, firefox),
        )

        assertEquals(Reason.UnmanagedBrowser, enforcement.suspend[firefox])
        assertNull(enforcement.suspend[chrome])
        assertNull(enforcement.suspend[notABrowser])
    }

    /**
     * Suspending every alternative browser on a phone with no filtering set up
     * would be a restriction nobody chose.
     */
    @Test
    fun `browsers are left alone unless the family asked`() {
        val enforcement = evaluate(
            policy(),
            installed = setOf(chrome, firefox),
            browsers = setOf(chrome, firefox),
        )

        assertTrue(enforcement.suspend.isEmpty())
    }

    /**
     * With no manageable browser on the phone, pausing the rest leaves no web at
     * all — which is not what the switch says it does.
     */
    @Test
    fun `browsers are left alone when none of them can be managed`() {
        val enforcement = evaluate(
            policy(browser = BrowserPolicy(suspendUnmanagedBrowsers = true)),
            installed = setOf(firefox),
            browsers = setOf(firefox),
        )

        assertTrue(enforcement.suspend.isEmpty())
    }

    /** The safety allowlist outranks web filtering, as it outranks everything. */
    @Test
    fun `a safety-exempt browser is never suspended`() {
        val enforcement = evaluate(
            policy(browser = BrowserPolicy(suspendUnmanagedBrowsers = true))
                .copy(safetyAllowlist = setOf(firefox)),
            installed = setOf(firefox),
            browsers = setOf(firefox),
        )

        assertTrue(enforcement.suspend.isEmpty())
    }

    // ---------- helpers ----------

    private fun evaluate(
        policy: Policy,
        installed: Set<PackageName> = emptySet(),
        browsers: Set<PackageName> = emptySet(),
    ) = PolicyEngine.evaluate(
        policy = policy,
        installedPackages = installed,
        usage = UsageSnapshot.empty(
            PolicyClock.policyDay(now, zone, policy.dayBoundaryHour),
        ),
        browsers = browsers,
        now = now,
    )

    private fun policy(
        dns: DnsFilter? = null,
        browser: BrowserPolicy = BrowserPolicy.OFF,
    ) = Policy(
        version = 1L,
        childId = ChildId("test"),
        timeZone = zone,
        dnsFilter = dns,
        browserPolicy = browser,
    )
}
