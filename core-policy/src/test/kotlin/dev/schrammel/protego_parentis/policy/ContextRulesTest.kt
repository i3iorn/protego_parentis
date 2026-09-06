package dev.schrammel.protego_parentis.policy

import dev.schrammel.protego_parentis.model.Category
import dev.schrammel.protego_parentis.model.CategoryId
import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.model.DeviceContext
import dev.schrammel.protego_parentis.model.DeviceTag
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.PlaceId
import dev.schrammel.protego_parentis.model.RecurringWindow
import dev.schrammel.protego_parentis.model.RuleEffect
import dev.schrammel.protego_parentis.model.RuleTarget
import dev.schrammel.protego_parentis.model.WeeklyBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
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

    /**
     * A Wednesday mid-morning, well away from any window these tests define.
     *
     * Most conditions here have nothing to do with the clock, but #101 made the
     * moment a required argument rather than a defaulted one — a time-window
     * rule that silently never fires because a caller omitted the clock is the
     * failure that choice exists to prevent. These helpers supply it once so
     * the tests that do not care about time do not have to mention it.
     */
    private val midweek = zdt("2026-08-19", "10:00")

    private fun holds(condition: Condition, context: DeviceContext, at: ZonedDateTime = midweek) =
        ContextRules.holds(condition, context, at)

    private fun evaluate(
        rules: List<ContextRule>,
        context: DeviceContext,
        hierarchy: ResolvedHierarchy = empty,
        at: ZonedDateTime = midweek,
    ) = ContextRules.evaluate(rules, context, hierarchy, ALL_PACKAGES, at)

    // ---------- conditions ----------

    @Test
    fun `a wifi condition holds only on that exact network`() {
        val onSchool = DeviceContext(wifiSsid = school)
        val onHome = DeviceContext(wifiSsid = home)

        assertTrue(holds(Condition.OnWifi(school), onSchool))
        assertFalse(holds(Condition.OnWifi(school), onHome))
    }

    @Test
    fun `a wifi condition fails when we could not read the network`() {
        // The critical case: a missing location permission leaves the SSID null.
        // That must fail the condition, never pass it, or a restriction would
        // silently disappear the moment a permission was revoked.
        assertFalse(holds(Condition.OnWifi(school), DeviceContext.UNKNOWN))
    }

    @Test
    fun `a proximity condition holds when the device is in range`() {
        val near = DeviceContext(nearbyDevices = setOf(dadsPhone))

        assertTrue(holds(Condition.NearDevice(dadsPhone), near))
        assertFalse(holds(Condition.NearDevice(dadsPhone), DeviceContext.UNKNOWN))
    }

    @Test
    fun `a place condition holds inside that place`() {
        val atSchool = DeviceContext(insidePlaces = setOf(schoolPlace))

        assertTrue(holds(Condition.AtPlace(schoolPlace), atSchool))
        assertFalse(holds(Condition.AtPlace(schoolPlace), DeviceContext.UNKNOWN))
    }

    @Test
    fun `a battery condition holds at or under the threshold`() {
        assertTrue(holds(Condition.BatteryBelow(20), DeviceContext(batteryPercent = 20)))
        assertTrue(holds(Condition.BatteryBelow(20), DeviceContext(batteryPercent = 5)))
        assertFalse(holds(Condition.BatteryBelow(20), DeviceContext(batteryPercent = 21)))
    }

    @Test
    fun `a battery condition fails when the level could not be read`() {
        // Same fail-closed convention as Wi-Fi: an unreadable level must not be
        // indistinguishable from "battery is fine".
        assertFalse(holds(Condition.BatteryBelow(20), DeviceContext.UNKNOWN))
    }

    @Test
    fun `always holds with nothing known at all`() {
        assertTrue(holds(Condition.Always, DeviceContext.UNKNOWN))
    }

    // ---------- combining ----------

    @Test
    fun `all-of needs every part`() {
        val condition = Condition.AllOf(
            listOf(Condition.OnWifi(school), Condition.AtPlace(schoolPlace)),
        )

        assertTrue(
            holds(
                condition,
                DeviceContext(wifiSsid = school, insidePlaces = setOf(schoolPlace)),
            ),
        )
        assertFalse(holds(condition, DeviceContext(wifiSsid = school)))
    }

    @Test
    fun `any-of needs one part`() {
        val condition = Condition.AnyOf(
            listOf(Condition.AtPlace(schoolPlace), Condition.NearDevice(dadsPhone)),
        )

        assertTrue(holds(condition, DeviceContext(nearbyDevices = setOf(dadsPhone))))
        assertFalse(holds(condition, DeviceContext.UNKNOWN))
    }

    @Test
    fun `not inverts`() {
        val awayFromHome = Condition.Not(Condition.OnWifi(home))

        assertTrue(holds(awayFromHome, DeviceContext(wifiSsid = school)))
        assertFalse(holds(awayFromHome, DeviceContext(wifiSsid = home)))
    }

    @Test
    fun `an empty all-of holds and an empty any-of does not`() {
        // A rule still being built has no conditions chosen. It must not start
        // blocking things on its own.
        assertTrue(holds(Condition.AllOf(emptyList()), DeviceContext.UNKNOWN))
        assertFalse(holds(Condition.AnyOf(emptyList()), DeviceContext.UNKNOWN))
    }

    // ---------- time windows (#101) ----------

    private fun window(
        startingOn: String,
        start: String,
        end: String,
        parityAnchor: String? = null,
    ) = Condition.DuringWindow(
        RecurringWindow(
            days = setOf(weekdayOf(startingOn)),
            start = LocalTime.parse(start),
            end = LocalTime.parse(end),
            parityAnchor = parityAnchor?.let(LocalDate::parse),
        ),
    )

    @Test
    fun `a same-day window holds inside it and not outside`() {
        val schoolHours = window(startingOn = "2026-08-21", start = "08:00", end = "15:00")

        assertFalse(holds(schoolHours, DeviceContext.UNKNOWN, zdt("2026-08-21", "07:59")))
        assertTrue(holds(schoolHours, DeviceContext.UNKNOWN, zdt("2026-08-21", "08:00")))
        assertTrue(holds(schoolHours, DeviceContext.UNKNOWN, zdt("2026-08-21", "14:59")))
        // Half-open, exactly as a schedule is: a window ending at 15:00 is over
        // at 15:00 rather than still running.
        assertFalse(holds(schoolHours, DeviceContext.UNKNOWN, zdt("2026-08-21", "15:00")))
    }

    @Test
    fun `a window does not hold on a day it does not run`() {
        val fridayOnly = window(startingOn = "2026-08-21", start = "08:00", end = "15:00")

        assertFalse(holds(fridayOnly, DeviceContext.UNKNOWN, zdt("2026-08-22", "10:00")))
    }

    /**
     * The case that made this worth sharing [ScheduleResolver] rather than
     * writing the arithmetic again: a bedtime starting Friday is still running
     * at 02:00 on Saturday, a day the window does not itself start on.
     */
    @Test
    fun `a window crossing midnight still holds after midnight`() {
        val bedtime = window(startingOn = "2026-08-21", start = "22:30", end = "07:00")

        assertFalse(holds(bedtime, DeviceContext.UNKNOWN, zdt("2026-08-21", "22:29")))
        assertTrue(holds(bedtime, DeviceContext.UNKNOWN, zdt("2026-08-21", "22:30")))
        assertTrue(holds(bedtime, DeviceContext.UNKNOWN, zdt("2026-08-22", "00:00")))
        assertTrue(holds(bedtime, DeviceContext.UNKNOWN, zdt("2026-08-22", "06:59")))
        assertFalse(holds(bedtime, DeviceContext.UNKNOWN, zdt("2026-08-22", "07:00")))
    }

    /** #83's alternating weeks, carried across to a condition unchanged. */
    @Test
    fun `an every-other-week window skips the weeks between`() {
        val alternating = window(
            startingOn = "2026-08-21",
            start = "08:00",
            end = "15:00",
            parityAnchor = "2026-08-21",
        )

        assertTrue(holds(alternating, DeviceContext.UNKNOWN, zdt("2026-08-21", "10:00")))
        assertFalse(holds(alternating, DeviceContext.UNKNOWN, zdt("2026-08-28", "10:00")))
        assertTrue(holds(alternating, DeviceContext.UNKNOWN, zdt("2026-09-04", "10:00")))
        // Parity is exact 7-day steps, so it holds before the anchor too.
        assertFalse(holds(alternating, DeviceContext.UNKNOWN, zdt("2026-08-14", "10:00")))
    }

    /**
     * The point of #101: a window is a condition like any other, so it composes
     * with the ones that were already there. "Games are blocked during school
     * hours, but only on the school Wi-Fi" is one rule, not two systems.
     */
    @Test
    fun `a window composes with a wifi condition`() {
        val duringSchoolOnSchoolWifi = Condition.AllOf(
            listOf(
                window(startingOn = "2026-08-21", start = "08:00", end = "15:00"),
                Condition.OnWifi(school),
            ),
        )
        val onSchoolWifi = DeviceContext(wifiSsid = school)

        val onHomeWifi = DeviceContext(wifiSsid = home)

        assertTrue(holds(duringSchoolOnSchoolWifi, onSchoolWifi, zdt("2026-08-21", "10:00")))
        // Right place, wrong time.
        assertFalse(holds(duringSchoolOnSchoolWifi, onSchoolWifi, zdt("2026-08-21", "16:00")))
        // Right time, wrong place.
        assertFalse(holds(duringSchoolOnSchoolWifi, onHomeWifi, zdt("2026-08-21", "10:00")))
    }

    @Test
    fun `a window rule blocks its target only while the window runs`() {
        val rules = listOf(rule(window(startingOn = "2026-08-21", start = "08:00", end = "15:00")))

        val during = evaluate(rules, DeviceContext.UNKNOWN, at = zdt("2026-08-21", "10:00"))
        val after = evaluate(rules, DeviceContext.UNKNOWN, at = zdt("2026-08-21", "16:00"))

        assertEquals(setOf(GAME), during.blocked.keys)
        assertTrue(after.blocked.isEmpty())
    }

    @Test
    fun `a pathologically nested condition does not blow the stack`() {
        // Conditions come from storage and could be corrupt. This runs inside the
        // enforcement service, so recursing until the stack dies is not an option.
        var condition: Condition = Condition.Always
        repeat(500) { condition = Condition.Not(condition) }

        // The assertion is simply that this returns at all.
        holds(condition, DeviceContext.UNKNOWN)
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
