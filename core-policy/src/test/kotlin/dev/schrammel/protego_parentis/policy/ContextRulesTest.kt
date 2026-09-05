package dev.schrammel.protego_parentis.policy

import dev.schrammel.protego_parentis.model.Category
import dev.schrammel.protego_parentis.model.CategoryId
import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.model.DeviceContext
import dev.schrammel.protego_parentis.model.DeviceTag
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.PlaceId
import dev.schrammel.protego_parentis.model.RuleEffect
import dev.schrammel.protego_parentis.model.RuleTarget
import dev.schrammel.protego_parentis.model.WeeklyBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class ContextRulesTest {

    private val school = "School-WiFi"
    private val home = "Home"
    private val dadsPhone = DeviceTag("dads-phone")
    private val schoolPlace = PlaceId("school")
    private val games = CategoryId("games")

    private val empty = CategoryHierarchy.resolve(emptyList())

    private fun rule(
        condition: Condition,
        effect: RuleEffect = RuleEffect.Block,
        target: RuleTarget = RuleTarget.Apps(setOf(GAME)),
        enabled: Boolean = true,
        name: String = "Rule",
    ) = ContextRule("r-$name", name, enabled, condition, effect, target)

    private fun evaluate(
        rules: List<ContextRule>,
        context: DeviceContext,
        hierarchy: ResolvedHierarchy = empty,
    ) = ContextRules.evaluate(rules, context, hierarchy, ALL_PACKAGES)

    // ---------- conditions ----------

    @Test
    fun `a wifi condition holds only on that exact network`() {
        val onSchool = DeviceContext(wifiSsid = school)
        val onHome = DeviceContext(wifiSsid = home)

        assertTrue(ContextRules.holds(Condition.OnWifi(school), onSchool))
        assertFalse(ContextRules.holds(Condition.OnWifi(school), onHome))
    }

    @Test
    fun `a wifi condition fails when we could not read the network`() {
        // The critical case: a missing location permission leaves the SSID null.
        // That must fail the condition, never pass it, or a restriction would
        // silently disappear the moment a permission was revoked.
        assertFalse(ContextRules.holds(Condition.OnWifi(school), DeviceContext.UNKNOWN))
    }

    @Test
    fun `a proximity condition holds when the device is in range`() {
        val near = DeviceContext(nearbyDevices = setOf(dadsPhone))

        assertTrue(ContextRules.holds(Condition.NearDevice(dadsPhone), near))
        assertFalse(ContextRules.holds(Condition.NearDevice(dadsPhone), DeviceContext.UNKNOWN))
    }

    @Test
    fun `a place condition holds inside that place`() {
        val atSchool = DeviceContext(insidePlaces = setOf(schoolPlace))

        assertTrue(ContextRules.holds(Condition.AtPlace(schoolPlace), atSchool))
        assertFalse(ContextRules.holds(Condition.AtPlace(schoolPlace), DeviceContext.UNKNOWN))
    }

    @Test
    fun `a battery condition holds at or under the threshold`() {
        assertTrue(ContextRules.holds(Condition.BatteryBelow(20), DeviceContext(batteryPercent = 20)))
        assertTrue(ContextRules.holds(Condition.BatteryBelow(20), DeviceContext(batteryPercent = 5)))
        assertFalse(ContextRules.holds(Condition.BatteryBelow(20), DeviceContext(batteryPercent = 21)))
    }

    @Test
    fun `a battery condition fails when the level could not be read`() {
        // Same fail-closed convention as Wi-Fi: an unreadable level must not be
        // indistinguishable from "battery is fine".
        assertFalse(ContextRules.holds(Condition.BatteryBelow(20), DeviceContext.UNKNOWN))
    }

    @Test
    fun `always holds with nothing known at all`() {
        assertTrue(ContextRules.holds(Condition.Always, DeviceContext.UNKNOWN))
    }

    // ---------- combining ----------

    @Test
    fun `all-of needs every part`() {
        val condition = Condition.AllOf(
            listOf(Condition.OnWifi(school), Condition.AtPlace(schoolPlace)),
        )

        assertTrue(
            ContextRules.holds(
                condition,
                DeviceContext(wifiSsid = school, insidePlaces = setOf(schoolPlace)),
            ),
        )
        assertFalse(ContextRules.holds(condition, DeviceContext(wifiSsid = school)))
    }

    @Test
    fun `any-of needs one part`() {
        val condition = Condition.AnyOf(
            listOf(Condition.AtPlace(schoolPlace), Condition.NearDevice(dadsPhone)),
        )

        assertTrue(ContextRules.holds(condition, DeviceContext(nearbyDevices = setOf(dadsPhone))))
        assertFalse(ContextRules.holds(condition, DeviceContext.UNKNOWN))
    }

    @Test
    fun `not inverts`() {
        val awayFromHome = Condition.Not(Condition.OnWifi(home))

        assertTrue(ContextRules.holds(awayFromHome, DeviceContext(wifiSsid = school)))
        assertFalse(ContextRules.holds(awayFromHome, DeviceContext(wifiSsid = home)))
    }

    @Test
    fun `an empty all-of holds and an empty any-of does not`() {
        // A rule still being built has no conditions chosen. It must not start
        // blocking things on its own.
        assertTrue(ContextRules.holds(Condition.AllOf(emptyList()), DeviceContext.UNKNOWN))
        assertFalse(ContextRules.holds(Condition.AnyOf(emptyList()), DeviceContext.UNKNOWN))
    }

    @Test
    fun `a pathologically nested condition does not blow the stack`() {
        // Conditions come from storage and could be corrupt. This runs inside the
        // enforcement service, so recursing until the stack dies is not an option.
        var condition: Condition = Condition.Always
        repeat(500) { condition = Condition.Not(condition) }

        // The assertion is simply that this returns at all.
        ContextRules.holds(condition, DeviceContext.UNKNOWN)
    }

    // ---------- effects ----------

    @Test
    fun `a block applies only while its condition holds`() {
        val rules = listOf(rule(Condition.OnWifi(school)))

        assertEquals(
            setOf(GAME),
            evaluate(rules, DeviceContext(wifiSsid = school)).blocked.keys,
        )
        assertEquals(
            emptySet<PackageName>(),
            evaluate(rules, DeviceContext(wifiSsid = home)).blocked.keys,
        )
    }

    @Test
    fun `a disabled rule never applies`() {
        val rules = listOf(rule(Condition.Always, enabled = false))

        assertEquals(emptySet<PackageName>(), evaluate(rules, DeviceContext.UNKNOWN).blocked.keys)
    }

    @Test
    fun `blocking everything covers every installed app`() {
        val rules = listOf(rule(Condition.Always, target = RuleTarget.Everything))

        assertEquals(ALL_PACKAGES, evaluate(rules, DeviceContext.UNKNOWN).blocked.keys)
    }

    @Test
    fun `a category target follows nesting`() {
        val hierarchy = CategoryHierarchy.resolve(
            listOf(
                Category(
                    id = CategoryId("fun"),
                    name = "Fun",
                    budget = WeeklyBudget.UNLIMITED,
                    subcategories = setOf(games),
                ),
                Category(games, "Games", WeeklyBudget.UNLIMITED, setOf(GAME)),
            ),
        )
        val rules = listOf(
            rule(Condition.Always, target = RuleTarget.Categories(setOf(CategoryId("fun")))),
        )

        assertEquals(
            setOf(GAME),
            evaluate(rules, DeviceContext.UNKNOWN, hierarchy).blocked.keys,
        )
    }

    @Test
    fun `a mixed target covers its loose apps and its category together`() {
        val hierarchy = CategoryHierarchy.resolve(
            listOf(Category(games, "Games", WeeklyBudget.UNLIMITED, setOf(GAME))),
        )
        val rules = listOf(
            rule(Condition.Always, target = RuleTarget.Mixed(packages = setOf(HOMEWORK), categories = setOf(games))),
        )

        assertEquals(
            setOf(GAME, HOMEWORK),
            evaluate(rules, DeviceContext.UNKNOWN, hierarchy).blocked.keys,
        )
    }

    @Test
    fun `an allow beats a block`() {
        // "Homework apps are fine at school" must survive "block everything at
        // school", or the rules become a puzzle rather than a tool.
        val rules = listOf(
            rule(Condition.Always, RuleEffect.Block, RuleTarget.Everything, name = "block"),
            rule(Condition.Always, RuleEffect.Allow, RuleTarget.Apps(setOf(HOMEWORK)), name = "allow"),
        )

        val outcome = evaluate(rules, DeviceContext.UNKNOWN)

        assertFalse(HOMEWORK in outcome.blocked)
        assertTrue(HOMEWORK in outcome.allowed)
        assertTrue(GAME in outcome.blocked)
    }

    @Test
    fun `the block names the rule that caused it`() {
        val rules = listOf(rule(Condition.OnWifi(school), name = "No games at school"))

        val outcome = evaluate(rules, DeviceContext(wifiSsid = school))

        assertEquals("No games at school", outcome.blocked.getValue(GAME).name)
    }

    // ---------- budgets ----------

    @Test
    fun `a rule can replace the device budget`() {
        val rules = listOf(
            rule(
                Condition.OnWifi(home),
                RuleEffect.SetDeviceBudget(WeeklyBudget.flat(4.hours)),
                RuleTarget.Everything,
            ),
        )

        assertEquals(
            WeeklyBudget.flat(4.hours),
            evaluate(rules, DeviceContext(wifiSsid = home)).deviceBudget,
        )
    }

    @Test
    fun `the tightest budget wins when several apply`() {
        val rules = listOf(
            rule(
                Condition.Always,
                RuleEffect.SetDeviceBudget(WeeklyBudget.flat(4.hours)),
                RuleTarget.Everything,
                name = "generous",
            ),
            rule(
                Condition.Always,
                RuleEffect.SetDeviceBudget(WeeklyBudget.flat(30.minutes)),
                RuleTarget.Everything,
                name = "strict",
            ),
        )

        assertEquals(
            WeeklyBudget.flat(30.minutes),
            evaluate(rules, DeviceContext.UNKNOWN).deviceBudget,
        )
    }

    @Test
    fun `no budget rule leaves the ordinary limit alone`() {
        assertNull(evaluate(listOf(rule(Condition.Always)), DeviceContext.UNKNOWN).deviceBudget)
    }

    @Test
    fun `active rules are reported for showing on screen`() {
        val rules = listOf(
            rule(Condition.OnWifi(school), name = "at school"),
            rule(Condition.OnWifi(home), name = "at home"),
        )

        val outcome = evaluate(rules, DeviceContext(wifiSsid = school))

        assertEquals(listOf("at school"), outcome.activeRules.map { it.name })
    }
}
