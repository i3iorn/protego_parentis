package dev.schrammel.protego_parentis.rules

import dev.schrammel.protego_parentis.model.AutoApproval
import dev.schrammel.protego_parentis.model.AutoApprovalWindow
import dev.schrammel.protego_parentis.model.CategoryId
import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.model.PlaceId
import dev.schrammel.protego_parentis.model.PlaceRef
import dev.schrammel.protego_parentis.model.RuleAction
import dev.schrammel.protego_parentis.model.RuleEffect
import dev.schrammel.protego_parentis.model.RuleTarget
import dev.schrammel.protego_parentis.model.ScheduleBlock
import java.time.DayOfWeek
import kotlin.time.Duration.Companion.minutes
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The share-ruleset contract: a bundle carries every rule AND everything the
 * rules depend on (SC-rule-share), round-trips through JSON unchanged, and
 * refuses anything that is not a ruleset this build understands.
 */
@RunWith(RobolectricTestRunner::class)
class RuleBundleTest {

    private val snapshot = PolicySnapshot(
        version = 42L,
        dailyLimitMinutes = 120,
        categories = listOf(
            StoredCategory(id = "cat-games", name = "Games", limitMinutes = 60, packages = setOf("com.example.game")),
        ),
        appLimits = mapOf("com.example.game" to 30),
        exemptPackages = emptySet(),
        blockedPackages = setOf("com.example.bad"),
        hiddenPackages = emptySet(),
        networkBlockedPackages = emptySet(),
        installPolicy = "NOTIFY",
        installWindowUntil = null,
        webFilterProfile = "OFF",
        webFilterCustomHost = "",
        webFilterSafeSearch = false,
        webFilterYoutube = false,
        webFilterIncognito = false,
        webFilterBlockedUrls = emptySet(),
        webFilterAllowedUrls = emptySet(),
        webFilterSuspendBrowsers = false,
        webFilterCountVisits = false,
        goalEnabled = false,
        goalMaxMinutes = 0,
        goalPrize = "",
        disabledFeatures = emptySet(),
    )

    private val schedule = StoredSchedule(
        id = "bedtime",
        name = "Bedtime",
        days = setOf(DayOfWeek.MONDAY.value, DayOfWeek.TUESDAY.value),
        startMinuteOfDay = 22 * 60,
        endMinuteOfDay = 7 * 60,
        packages = setOf("com.example.game"),
        mode = ScheduleBlock.Mode.EXCLUSIVE,
        enabled = true,
        kind = ScheduleBlock.Kind.BEDTIME,
    )

    private val place = PlaceRef(id = PlaceId("place-home"), name = "Home", radiusMetres = 150)

    private val autoApproval = AutoApproval(
        id = "auto-1",
        name = "After school",
        enabled = true,
        maxAmount = 30.minutes,
        maxPerWindow = 2,
        window = AutoApprovalWindow.DAY,
    )

    private val contextRule = ContextRule(
        id = "rule-1",
        name = "School hours lockout",
        enabled = true,
        condition = Condition.AtPlace(place.id),
        actions = listOf(RuleAction(RuleEffect.Block(), RuleTarget.Categories(setOf(CategoryId("cat-games"))))),
        exceptWhen = null,
        pausedUntil = null,
    )

    private val bundle = RuleBundle(
        snapshot = snapshot,
        contextRules = listOf(contextRule),
        schedules = listOf(schedule),
        placeRefs = listOf(place),
        autoApprovals = listOf(autoApproval),
    )

    @Test
    fun `a bundle round-trips through JSON`() {
        val back = RuleBundle.decode(bundle.toJson())
        assertNotNull(back)
        assertEquals(snapshot, back!!.snapshot)
        assertEquals(listOf(contextRule), back.contextRules)
        assertEquals(listOf(schedule), back.schedules)
        assertEquals(listOf(place), back.placeRefs)
        assertEquals(listOf(autoApproval), back.autoApprovals)
    }

    @Test
    fun `a rule's dependencies ride in the bundle`() {
        val json = bundle.toJson()
        assertTrue("the place a rule resolves against must be exported", json.contains("place-home"))
        assertTrue("the category a rule targets must be exported", json.contains("cat-games"))
        assertTrue("the schedule rules refer to must be exported", json.contains("bedtime"))
    }

    @Test
    fun `a foreign or hand-edited file is refused`() {
        assertNull(RuleBundle.decode(null))
        assertNull(RuleBundle.decode(""))
        assertNull(RuleBundle.decode("not json at all"))
        assertNull(RuleBundle.decode("""{"format": 99}"""))
        assertNull(RuleBundle.decode("""{"format": 1}"""))
    }

    @Test
    fun `an unreadable rule is dropped, not fatal`() {
        val json = JSONObject(bundle.toJson())
        // The rules ride as the store's own encoded array; corrupt one
        // condition inside it and the import survives with fewer rules.
        val rules = JSONArray(json.getString("context_rules"))
        rules.getJSONObject(0).put("condition", "broken")
        json.put("context_rules", rules.toString())
        val back = RuleBundle.decode(json.toString())
        assertNotNull(back)
        assertTrue(back!!.contextRules.isEmpty())
    }
}
