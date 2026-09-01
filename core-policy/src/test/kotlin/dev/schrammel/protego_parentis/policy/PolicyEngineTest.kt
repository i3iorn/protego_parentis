package dev.schrammel.protego_parentis.policy

import dev.schrammel.protego_parentis.model.AppRule
import dev.schrammel.protego_parentis.model.BudgetLevel
import dev.schrammel.protego_parentis.model.DeviceState
import dev.schrammel.protego_parentis.model.GrantTarget
import dev.schrammel.protego_parentis.model.Reason
import dev.schrammel.protego_parentis.model.WeeklyBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class PolicyEngineTest {

    // ---------- safety invariant ----------

    @Test
    fun `safety allowlisted app is never suspended even when everything else is`() {
        val result = PolicyEngine.evaluate(
            policy = policy(
                deviceBudget = WeeklyBudget.flat(1.hours),
                appRules = mapOf(DIALER to AppRule.Blocked()),
                schedules = listOf(bedtime(startingOn = "2026-08-21", allowed = emptySet())),
            ),
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21", deviceTotal = 5.hours),
            now = at("2026-08-22", "02:00"),
        )

        assertNull(
            "the dialer must survive a parent block, bedtime, and an exhausted budget",
            result.suspend[DIALER],
        )
    }

    @Test
    fun `always-allowed rule exempts an app from the device budget`() {
        val result = PolicyEngine.evaluate(
            policy = policy(
                deviceBudget = WeeklyBudget.flat(1.hours),
                appRules = mapOf(HOMEWORK to AppRule.AlwaysAllowed),
            ),
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21", deviceTotal = 2.hours),
            now = at("2026-08-21", "18:00"),
        )

        assertNull(result.suspend[HOMEWORK])
        assertTrue(result.suspend.containsKey(YOUTUBE))
    }

    // ---------- device budget ----------

    @Test
    fun `exhausted device budget suspends every non-exempt app until the day boundary`() {
        val now = at("2026-08-21", "18:00")
        val result = PolicyEngine.evaluate(
            policy = policy(deviceBudget = WeeklyBudget.flat(2.hours)),
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21", deviceTotal = 2.hours),
            now = now,
        )

        assertEquals(BudgetLevel.EXHAUSTED, result.deviceBudget.level)
        assertEquals(setOf(CLOCK, YOUTUBE, GAME, HOMEWORK), result.suspend.keys)

        val reason = result.suspend[YOUTUBE]
        assertTrue(reason is Reason.DeviceBudgetExhausted)
        assertEquals(at("2026-08-22", "04:00"), reason?.liftsAt)
    }

    @Test
    fun `unused budget leaves everything running`() {
        val result = PolicyEngine.evaluate(
            policy = policy(deviceBudget = WeeklyBudget.flat(2.hours)),
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21", deviceTotal = 30.minutes),
            now = at("2026-08-21", "18:00"),
        )

        assertEquals(emptyMap<Any, Any>(), result.suspend)
        assertEquals(90.minutes, result.deviceBudget.remaining)
        assertEquals(BudgetLevel.NORMAL, result.deviceBudget.level)
    }

    @Test
    fun `weekday and weekend allowances differ`() {
        val schoolWeek = WeeklyBudget.schoolWeek(schoolNight = 1.hours, weekend = 3.hours)
        val friday = "2026-08-21"
        val monday = "2026-08-17"

        // Sanity-check the fixture dates are the weekdays the test assumes.
        assertEquals(java.time.DayOfWeek.FRIDAY, weekdayOf(friday))
        assertEquals(java.time.DayOfWeek.MONDAY, weekdayOf(monday))

        val onFriday = PolicyEngine.evaluate(
            policy = policy(deviceBudget = schoolWeek),
            installedPackages = ALL_PACKAGES,
            usage = usage(friday, deviceTotal = 90.minutes),
            now = at(friday, "18:00"),
        )
        val onMonday = PolicyEngine.evaluate(
            policy = policy(deviceBudget = schoolWeek),
            installedPackages = ALL_PACKAGES,
            usage = usage(monday, deviceTotal = 90.minutes),
            now = at(monday, "18:00"),
        )

        assertEquals(BudgetLevel.NORMAL, onFriday.deviceBudget.level)
        assertEquals(BudgetLevel.EXHAUSTED, onMonday.deviceBudget.level)
    }

    // ---------- the warning ladder ----------

    @Test
    fun `warning ladder moves through notice, grace, then exhausted`() {
        fun levelAfter(used: Duration) = PolicyEngine.evaluate(
            policy = policy(deviceBudget = WeeklyBudget.flat(1.hours)),
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21", deviceTotal = used),
            now = at("2026-08-21", "18:00"),
        ).deviceBudget.level

        assertEquals(BudgetLevel.NORMAL, levelAfter(40.minutes))
        assertEquals(BudgetLevel.NOTICE, levelAfter(50.minutes))
        assertEquals(BudgetLevel.NOTICE, levelAfter(54.minutes))
        assertEquals(BudgetLevel.GRACE, levelAfter(55.minutes))
        assertEquals(BudgetLevel.GRACE, levelAfter(59.minutes))
        assertEquals(BudgetLevel.EXHAUSTED, levelAfter(60.minutes))
        assertEquals(BudgetLevel.EXHAUSTED, levelAfter(90.minutes))
    }

    @Test
    fun `unlimited budget never leaves normal`() {
        val result = PolicyEngine.evaluate(
            policy = policy(deviceBudget = WeeklyBudget.UNLIMITED),
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21", deviceTotal = 12.hours),
            now = at("2026-08-21", "18:00"),
        )

        assertEquals(BudgetLevel.NORMAL, result.deviceBudget.level)
        assertTrue(result.deviceBudget.isUnlimited)
        assertEquals(emptyMap<Any, Any>(), result.suspend)
    }

    // ---------- bonus grants ----------

    @Test
    fun `bonus grant reopens an exhausted device budget`() {
        val now = at("2026-08-21", "18:00")
        val result = PolicyEngine.evaluate(
            policy = policy(deviceBudget = WeeklyBudget.flat(1.hours)),
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21", deviceTotal = 1.hours),
            grants = listOf(
                grant(GrantTarget.Device, 20.minutes, from = now, until = at("2026-08-22", "04:00")),
            ),
            now = now,
        )

        assertEquals(20.minutes, result.deviceBudget.remaining)
        assertEquals(20.minutes, result.deviceBudget.bonus)
        assertEquals(1.hours, result.deviceBudget.allowed)
        assertEquals(emptyMap<Any, Any>(), result.suspend)
    }

    @Test
    fun `expired grant is ignored`() {
        val result = PolicyEngine.evaluate(
            policy = policy(deviceBudget = WeeklyBudget.flat(1.hours)),
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21", deviceTotal = 1.hours),
            grants = listOf(
                grant(
                    GrantTarget.Device,
                    20.minutes,
                    from = at("2026-08-20", "18:00"),
                    until = at("2026-08-21", "04:00"),
                ),
            ),
            now = at("2026-08-21", "18:00"),
        )

        assertEquals(Duration.ZERO, result.deviceBudget.bonus)
        assertEquals(BudgetLevel.EXHAUSTED, result.deviceBudget.level)
    }

    @Test
    fun `an app grant does not extend the device budget`() {
        val now = at("2026-08-21", "18:00")
        val result = PolicyEngine.evaluate(
            policy = policy(deviceBudget = WeeklyBudget.flat(1.hours)),
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21", deviceTotal = 1.hours),
            grants = listOf(
                grant(GrantTarget.App(GAME), 20.minutes, from = now, until = at("2026-08-22", "04:00")),
            ),
            now = now,
        )

        assertEquals(Duration.ZERO, result.deviceBudget.bonus)
        assertTrue(result.suspend.containsKey(GAME))
    }

    // ---------- per-app budgets ----------

    @Test
    fun `app budget suspends only that app`() {
        val result = PolicyEngine.evaluate(
            policy = policy(appRules = mapOf(GAME to AppRule.Budgeted(WeeklyBudget.flat(30.minutes)))),
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21", deviceTotal = 3.hours, byPackage = mapOf(GAME to 30.minutes)),
            now = at("2026-08-21", "18:00"),
        )

        assertEquals(setOf(GAME), result.suspend.keys)
        assertEquals(BudgetLevel.EXHAUSTED, result.appBudgets.getValue(GAME).level)
    }

    @Test
    fun `app budget is reported even while the app is still running`() {
        val result = PolicyEngine.evaluate(
            policy = policy(appRules = mapOf(GAME to AppRule.Budgeted(WeeklyBudget.flat(1.hours)))),
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21", byPackage = mapOf(GAME to 20.minutes)),
            now = at("2026-08-21", "18:00"),
        )

        assertEquals(40.minutes, result.appBudgets.getValue(GAME).remaining)
        assertEquals(emptyMap<Any, Any>(), result.suspend)
    }

    // ---------- schedules ----------

    @Test
    fun `bedtime starting friday night is still active at two in the morning`() {
        val result = PolicyEngine.evaluate(
            policy = policy(schedules = listOf(bedtime(startingOn = "2026-08-21"))),
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21"),
            now = at("2026-08-22", "02:00"),
        )

        assertEquals(setOf(YOUTUBE, GAME, HOMEWORK), result.suspend.keys)
        assertNull("the alarm clock must survive bedtime", result.suspend[CLOCK])

        val reason = result.suspend[YOUTUBE]
        assertTrue(reason is Reason.ScheduleActive)
        assertEquals("Bedtime", (reason as Reason.ScheduleActive).scheduleName)
        assertEquals(at("2026-08-22", "07:00"), reason.liftsAt)
    }

    @Test
    fun `bedtime has lifted by eight in the morning`() {
        val result = PolicyEngine.evaluate(
            policy = policy(schedules = listOf(bedtime(startingOn = "2026-08-21"))),
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-22"),
            now = at("2026-08-22", "08:00"),
        )

        assertEquals(emptyMap<Any, Any>(), result.suspend)
        assertEquals(emptyList<Any>(), result.activeSchedules)
    }

    @Test
    fun `bedtime has not started at nine in the evening`() {
        val result = PolicyEngine.evaluate(
            policy = policy(schedules = listOf(bedtime(startingOn = "2026-08-21"))),
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21"),
            now = at("2026-08-21", "21:00"),
        )

        assertEquals(emptyMap<Any, Any>(), result.suspend)
    }

    // ---------- conflict resolution ----------

    @Test
    fun `a parent block outranks a schedule that would lift in the morning`() {
        val result = PolicyEngine.evaluate(
            policy = policy(
                appRules = mapOf(YOUTUBE to AppRule.Blocked()),
                schedules = listOf(bedtime(startingOn = "2026-08-21")),
            ),
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21"),
            now = at("2026-08-22", "02:00"),
        )

        assertEquals(Reason.ManuallyBlocked, result.suspend[YOUTUBE])
    }

    @Test
    fun `the reason shown is the one that lifts last`() {
        // Bedtime runs to 07:00; the budget resets at the 04:00 day boundary.
        // Bedtime lifts later, so bedtime is the honest answer to "until when".
        val result = PolicyEngine.evaluate(
            policy = policy(
                deviceBudget = WeeklyBudget.flat(1.hours),
                schedules = listOf(bedtime(startingOn = "2026-08-21")),
            ),
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21", deviceTotal = 2.hours),
            now = at("2026-08-22", "02:00"),
        )

        val reason = result.suspend[YOUTUBE]
        assertTrue("expected the schedule to win, got $reason", reason is Reason.ScheduleActive)
        assertEquals(at("2026-08-22", "07:00"), reason?.liftsAt)
    }

    @Test
    fun `hidden blocks are reported separately from suspensions`() {
        val result = PolicyEngine.evaluate(
            policy = policy(appRules = mapOf(GAME to AppRule.Blocked(hidden = true))),
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21"),
            now = at("2026-08-21", "18:00"),
        )

        assertEquals(setOf(GAME), result.hide)
        assertEquals(Reason.ManuallyBlocked, result.suspend[GAME])
    }

    // ---------- deferral ----------

    @Test
    fun `a call in progress defers suspension rather than cancelling it`() {
        val result = PolicyEngine.evaluate(
            policy = policy(deviceBudget = WeeklyBudget.flat(1.hours)),
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21", deviceTotal = 2.hours),
            deviceState = DeviceState(callInProgress = true),
            now = at("2026-08-21", "18:00"),
        )

        assertEquals(emptyMap<Any, Any>(), result.suspend)
        assertEquals(setOf(CLOCK, YOUTUBE, GAME, HOMEWORK), result.deferred.keys)
        assertTrue(result.reasonFor(YOUTUBE) is Reason.DeviceBudgetExhausted)
    }

    // ---------- alarm scheduling ----------

    @Test
    fun `next change is the notice threshold while an app is in the foreground`() {
        val now = at("2026-08-21", "10:00")
        val result = PolicyEngine.evaluate(
            policy = policy(deviceBudget = WeeklyBudget.flat(1.hours)),
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21"),
            deviceState = DeviceState(foregroundPackage = GAME),
            now = now,
        )

        // 60 minutes remain, so the notice fires in 50.
        assertEquals(now.plusSeconds(50 * 60), result.nextChangeAt)
    }

    @Test
    fun `next change falls back to the day boundary when the device is idle`() {
        val result = PolicyEngine.evaluate(
            policy = policy(deviceBudget = WeeklyBudget.flat(1.hours)),
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21"),
            deviceState = DeviceState.IDLE,
            now = at("2026-08-21", "10:00"),
        )

        assertEquals(at("2026-08-22", "04:00"), result.nextChangeAt)
    }

    @Test
    fun `next change is the schedule edge when bedtime is closer than the day boundary`() {
        val result = PolicyEngine.evaluate(
            policy = policy(schedules = listOf(bedtime(startingOn = "2026-08-21"))),
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21"),
            now = at("2026-08-21", "21:00"),
        )

        assertEquals(at("2026-08-21", "22:30"), result.nextChangeAt)
    }

    // ---------- bookkeeping ----------

    @Test
    fun `enforcement carries the policy version it was computed from`() {
        val result = PolicyEngine.evaluate(
            policy = policy().copy(version = 42L),
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21"),
            now = at("2026-08-21", "18:00"),
        )

        assertEquals(42L, result.policyVersion)
    }
}
