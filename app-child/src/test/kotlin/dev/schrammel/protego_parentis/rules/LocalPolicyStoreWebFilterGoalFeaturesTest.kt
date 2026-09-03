package dev.schrammel.protego_parentis.rules

import android.content.Context
import dev.schrammel.protego_parentis.model.BrowserPolicy
import dev.schrammel.protego_parentis.model.DailyGoal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes

/**
 * #42: `WebFilterStore`, `GoalStore` and `FeatureFlags` reached through
 * [LocalPolicyStore]'s passthrough properties, the same delegate-to-the-real-
 * store shape [LocalPolicyStore.bedtime] already uses for `ScheduleStore`.
 */
@RunWith(RobolectricTestRunner::class)
class LocalPolicyStoreWebFilterGoalFeaturesTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun store() = LocalPolicyStore(context)

    // ---------- web filter ----------

    @Test
    fun `web filter settings are remembered by a fresh store instance`() {
        store().webFilterProfile = DnsProfile.ADULT
        store().webFilterCustomHost = "example.com"
        store().browserPolicy = BrowserPolicy(
            forceSafeSearch = true,
            restrictYouTube = true,
            blockIncognito = true,
            blockedUrls = listOf("bad.example.com"),
            allowedUrls = listOf("homework.example.com"),
            suspendUnmanagedBrowsers = true,
        )
        store().webFilterCountVisits = true

        val reread = LocalPolicyStore(context)
        assertEquals(DnsProfile.ADULT, reread.webFilterProfile)
        assertEquals("example.com", reread.webFilterCustomHost)
        assertTrue(reread.browserPolicy.forceSafeSearch)
        assertEquals(listOf("bad.example.com"), reread.browserPolicy.blockedUrls)
        assertTrue(reread.webFilterCountVisits)
    }

    /** The actual storage this now goes through, not just the round trip. */
    @Test
    fun `web filter settings live in WebFilterStore`() {
        store().webFilterProfile = DnsProfile.STRICT

        assertEquals(DnsProfile.STRICT, WebFilterStore(context).profile)
    }

    // ---------- goal ----------

    @Test
    fun `a goal is remembered by a fresh store instance`() {
        store().goal = DailyGoal(
            enabled = true,
            maxUsage = 90.minutes,
            prize = "Extra bonus time",
            effectiveFrom = LocalDate.now(),
        )

        val reread = LocalPolicyStore(context).goal
        assertTrue(reread.enabled)
        assertEquals(90.minutes, reread.maxUsage)
        assertEquals("Extra bonus time", reread.prize)
    }

    /** GoalStore's own restart-detection still runs — this property is not a bypass. */
    @Test
    fun `saving the same goal twice does not move effectiveFrom`() {
        val today = LocalDate.now()
        store().goal = DailyGoal(enabled = true, maxUsage = 90.minutes, prize = "Prize", effectiveFrom = today)
        val firstEffectiveFrom = GoalStore(context).load(today).effectiveFrom

        store().goal = DailyGoal(enabled = true, maxUsage = 90.minutes, prize = "Changed prize", effectiveFrom = today)

        assertEquals(firstEffectiveFrom, GoalStore(context).load(today).effectiveFrom)
    }

    // ---------- features ----------

    @Test
    fun `nothing is disabled by default`() {
        assertTrue(store().disabledFeatures.isEmpty())
    }

    @Test
    fun `a disabled optional feature is remembered by a fresh store instance`() {
        store().disabledFeatures = setOf(Feature.WEB_ACTIVITY, Feature.CHAT)

        val reread = LocalPolicyStore(context).disabledFeatures
        assertEquals(setOf(Feature.WEB_ACTIVITY, Feature.CHAT), reread)
    }

    @Test
    fun `re-enabling a feature clears it`() {
        store().disabledFeatures = setOf(Feature.WEB_ACTIVITY, Feature.CHAT)

        store().disabledFeatures = setOf(Feature.CHAT)

        assertEquals(setOf(Feature.CHAT), LocalPolicyStore(context).disabledFeatures)
    }

    /** A non-optional feature can never appear here — FeatureFlags.setEnabled refuses it. */
    @Test
    fun `a non-optional feature cannot be disabled through this property`() {
        store().disabledFeatures = setOf(Feature.EMERGENCY, Feature.HISTORY, Feature.TRANSPARENCY)

        assertTrue(LocalPolicyStore(context).disabledFeatures.isEmpty())
        assertTrue(FeatureFlags(context).isEnabled(Feature.EMERGENCY))
    }
}
