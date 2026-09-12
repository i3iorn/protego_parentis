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
import kotlin.time.Duration
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

    private fun holds(
        condition: Condition,
        context: DeviceContext,
        at: ZonedDateTime = midweek,
        deviceUsage: Duration = Duration.ZERO,
    ) = ContextRules.holds(condition, context, at, deviceUsage)

    private fun evaluate(
        rules: List<ContextRule>,
        context: DeviceContext,
        hierarchy: ResolvedHierarchy = empty,
        at: ZonedDateTime = midweek,
        deviceUsage: Duration = Duration.ZERO,
    ) = ContextRules.evaluate(rules, context, hierarchy, ALL_PACKAGES, at, deviceUsage)

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

    @Test
    fun `an allowance condition holds once usage reaches the limit`() {
        val condition = Condition.AllowanceSpent(60)

        assertFalse(holds(condition, DeviceContext.UNKNOWN, deviceUsage = 59.minutes))
        assertTrue(holds(condition, DeviceContext.UNKNOWN, deviceUsage = 60.minutes))
        assertTrue(holds(condition, DeviceContext.UNKNOWN, deviceUsage = 90.minutes))
    }

    @Test
    fun `an allowance condition reports no end and no window edges`() {
        val condition = Condition.AllowanceSpent(60)

        assertNull(ContextRules.liftsAt(condition, midweek))
        assertTrue(ContextRules.windowsIn(condition).isEmpty())
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

    // ---------- when a rule lifts (#103) ----------

    @Test
    fun `a window condition says exactly when it lifts`() {
        val schoolHours = window(startingOn = "2026-08-21", start = "08:00", end = "15:00")

        assertEquals(
            zdt("2026-08-21", "15:00").toInstant(),
            ContextRules.liftsAt(schoolHours, zdt("2026-08-21", "10:00")),
        )
    }

    @Test
    fun `a condition with nothing clock-like about it says nothing`() {
        assertNull(ContextRules.liftsAt(Condition.OnWifi(school), zdt("2026-08-21", "10:00")))
        assertNull(ContextRules.liftsAt(Condition.Always, zdt("2026-08-21", "10:00")))
        assertNull(ContextRules.liftsAt(Condition.AtPlace(schoolPlace), zdt("2026-08-21", "10:00")))
    }

    /**
     * The composite stops as soon as its first branch does, so a branch that
     * cannot say when it ends only ever makes the real end earlier than the one
     * reported — which is the safe direction.
     */
    @Test
    fun `an all-of reports the earliest end its branches know`() {
        val early = window(startingOn = "2026-08-21", start = "09:00", end = "12:00")
        val late = window(startingOn = "2026-08-21", start = "08:00", end = "15:00")
        val at = zdt("2026-08-21", "10:00")

        assertEquals(
            zdt("2026-08-21", "12:00").toInstant(),
            ContextRules.liftsAt(Condition.AllOf(listOf(late, early)), at),
        )
        // The Wi-Fi branch drops out rather than poisoning the answer.
        assertEquals(
            zdt("2026-08-21", "15:00").toInstant(),
            ContextRules.liftsAt(Condition.AllOf(listOf(late, Condition.OnWifi(school))), at),
        )
        assertNull(
            ContextRules.liftsAt(Condition.AllOf(listOf(Condition.OnWifi(school))), at),
        )
    }

    /**
     * Even though both branches know their own ends. An OR holds while *any*
     * branch does, so a branch that is not holding yet can start later and carry
     * the block past anything computed from the branches that are — and a block
     * still running at the time the phone promised it would end is the app
     * having lied about its own rules.
     */
    @Test
    fun `an any-of promises nothing, because a branch can start later and extend it`() {
        val morning = window(startingOn = "2026-08-21", start = "08:00", end = "12:00")
        val afternoon = window(startingOn = "2026-08-21", start = "13:00", end = "17:00")

        assertNull(
            ContextRules.liftsAt(Condition.AnyOf(listOf(morning, afternoon)), zdt("2026-08-21", "10:00")),
        )
    }

    @Test
    fun `a negated condition promises nothing`() {
        val schoolHours = window(startingOn = "2026-08-21", start = "08:00", end = "15:00")

        // "Not during school" ends when school starts — a question about when a
        // condition begins, which this does not answer.
        assertNull(ContextRules.liftsAt(Condition.Not(schoolHours), zdt("2026-08-21", "16:00")))
    }

    @Test
    fun `a rule that is blocked but has no window reports no end`() {
        val rules = listOf(rule(Condition.OnWifi(school)))

        val outcome = evaluate(rules, DeviceContext(wifiSsid = school))

        assertEquals(setOf(GAME), outcome.blocked.keys)
        assertNull(ContextRules.liftsAt(outcome.blocked.getValue(GAME).condition, midweek))
    }

    // ---------- alarm edges (#103) ----------

    @Test
    fun `every window in a condition tree is found, however it is nested`() {
        val morning = window(startingOn = "2026-08-21", start = "08:00", end = "12:00")
        val evening = window(startingOn = "2026-08-21", start = "18:00", end = "20:00")

        val nested = Condition.AllOf(
            listOf(
                Condition.OnWifi(school),
                Condition.Not(morning),
                Condition.AnyOf(listOf(evening, Condition.AtPlace(schoolPlace))),
            ),
        )

        // Collected from under a Not and an AnyOf too: this module will not
        // promise those ends, but the edges are still when the answer changes.
        assertEquals(listOf(morning.window, evening.window), ContextRules.windowsIn(nested))
    }

    @Test
    fun `a condition with no windows contributes no edges`() {
        assertTrue(ContextRules.windowsIn(Condition.AllOf(listOf(Condition.OnWifi(school)))).isEmpty())
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

    /** #105: the migrated shape of an EXCLUSIVE-mode schedule. */
    @Test
    fun `everything-except covers every installed app but the ones it names`() {
        val rules = listOf(rule(Condition.Always, target = RuleTarget.EverythingExcept(setOf(CLOCK))))

        assertEquals(ALL_PACKAGES - CLOCK, evaluate(rules, DeviceContext.UNKNOWN).blocked.keys)
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
    fun `a safety-exempt rule also beats a block, and is reported separately from allow`() {
        val rules = listOf(
            rule(Condition.Always, RuleEffect.Block, RuleTarget.Everything, name = "block"),
            rule(Condition.Always, RuleEffect.SafetyExempt, RuleTarget.Apps(setOf(HOMEWORK)), name = "exempt"),
        )

        val outcome = evaluate(rules, DeviceContext.UNKNOWN)

        assertFalse(HOMEWORK in outcome.blocked)
        assertTrue(HOMEWORK in outcome.safetyExempt)
        // Not just an ordinary allow: PolicyEngine treats this set as the
        // stronger tier (network blocking, DNS filtering included), so the
        // two must not be conflated here either.
        assertFalse(HOMEWORK in outcome.allowed)
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
    fun `the tightest budget is judged by today, not by the weaker rule's weekly peak`() {
        // "generous" has the lower weekly peak (1 hour flat), but on the midweek
        // day these are evaluated at, "split" actually allows less (30 minutes on
        // a school night vs. 60). Picking by weekly peak alone would let
        // "generous" win here even though it is not the tighter rule today.
        val rules = listOf(
            rule(
                Condition.Always,
                RuleEffect.SetDeviceBudget(WeeklyBudget.flat(1.hours)),
                RuleTarget.Everything,
                name = "generous",
            ),
            rule(
                Condition.Always,
                RuleEffect.SetDeviceBudget(WeeklyBudget.schoolWeek(schoolNight = 30.minutes, weekend = 2.hours)),
                RuleTarget.Everything,
                name = "split",
            ),
        )

        assertEquals(
            WeeklyBudget.schoolWeek(schoolNight = 30.minutes, weekend = 2.hours),
            evaluate(rules, DeviceContext.UNKNOWN).deviceBudget,
        )
    }

    @Test
    fun `an allowance rule blocks its target once usage reaches the limit`() {
        val rules = listOf(rule(Condition.AllowanceSpent(60)))

        assertTrue(evaluate(rules, DeviceContext.UNKNOWN, deviceUsage = 30.minutes).blocked.isEmpty())
        assertEquals(setOf(GAME), evaluate(rules, DeviceContext.UNKNOWN, deviceUsage = 60.minutes).blocked.keys)
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
