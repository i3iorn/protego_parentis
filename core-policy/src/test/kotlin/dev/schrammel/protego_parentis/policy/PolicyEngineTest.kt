package dev.schrammel.protego_parentis.policy

import dev.schrammel.protego_parentis.model.AppRule
import dev.schrammel.protego_parentis.model.BudgetLevel
import dev.schrammel.protego_parentis.model.Category
import dev.schrammel.protego_parentis.model.CategoryId
import dev.schrammel.protego_parentis.model.ChildId
import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.model.DeviceState
import dev.schrammel.protego_parentis.model.GrantTarget
import dev.schrammel.protego_parentis.model.Policy
import dev.schrammel.protego_parentis.model.Reason
import dev.schrammel.protego_parentis.model.RuleEffect
import dev.schrammel.protego_parentis.model.RuleTarget
import dev.schrammel.protego_parentis.model.ScheduleBlock
import dev.schrammel.protego_parentis.model.ScheduleId
import dev.schrammel.protego_parentis.model.WeeklyBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class PolicyEngineTest {

    // ---------- wire compatibility with an older build ----------

    /**
     * [Policy.deviceBudget], [Policy.schedules], [AppRule.Budgeted]/
     * [AppRule.Blocked], and [Category.budget] all say, in their own doc
     * comments, that [PolicyEngine] no longer reads them — each was replaced
     * by an equivalent [ContextRule] under #105/#106. They stay on the type
     * only because a sibling phone still running a build from before that
     * migration can still send or receive one over sync (see
     * `docs/RULES-INVENTORY.md`'s `LocalPolicyStore` section), and a family
     * is not required to update every phone at once.
     *
     * The [policy] test helper always translates these into the equivalent
     * [ContextRule] before constructing a [Policy], the same conversion
     * `LocalPolicyStore.migrateToUnifiedRules`/`migrateAppRulesToUnifiedRules`
     * do for a real device — so every other test in this file, and every
     * other test in this suite, never actually exercises these fields being
     * non-default. This one constructs a [Policy] directly, the way an
     * *unmigrated* store still can, with every one of them set to something
     * that would suspend or restrict every app if [PolicyEngine] read it —
     * and asserts nothing is suspended. An older sibling phone (or a
     * downgrade, or a field a future refactor forgets to remove) populating
     * these must never silently change enforcement.
     */
    @Test
    fun `an unmigrated policy's dead fields do not affect enforcement`() {
        val lockdownSchedule = ScheduleBlock(
            id = ScheduleId("legacy-lockdown"),
            name = "Legacy lockdown",
            days = weekdayOf("2026-08-21").let(::setOf),
            start = LocalTime.MIN,
            end = LocalTime.of(23, 59),
            packages = emptySet(),
            mode = ScheduleBlock.Mode.EXCLUSIVE,
        )
        val lockedCategory = Category(
            id = CategoryId("legacy-category"),
            name = "Legacy category",
            budget = WeeklyBudget.flat(Duration.ZERO),
            packages = setOf(GAME),
        )

        val policy = Policy(
            version = 1L,
            childId = ChildId("child-1"),
            timeZone = ZONE,
            deviceBudget = WeeklyBudget.flat(Duration.ZERO),
            appRules = mapOf(
                GAME to AppRule.Blocked(),
                YOUTUBE to AppRule.Budgeted(WeeklyBudget.flat(Duration.ZERO)),
            ),
            categories = listOf(lockedCategory),
            schedules = listOf(lockdownSchedule),
            contextRules = emptyList(),
            safetyAllowlist = emptySet(),
        )

        val result = PolicyEngine.evaluate(
            policy = policy,
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21", deviceTotal = 5.hours),
            now = at("2026-08-21", "18:00"),
        )

        assertTrue(
            "a Policy whose only restrictions live in the fields #105/#106 stopped reading " +
                "must suspend nothing — suspended: ${result.suspend.keys}",
            result.suspend.isEmpty(),
        )
    }

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

    /**
     * A [RuleEffect.SafetyExempt] rule is a parent-configured version of the
     * same tier [AppRule.AlwaysAllowed]/the safety allowlist already occupy —
     * unlike a plain [RuleEffect.Allow], it has to reach network blocking too,
     * not just the device budget.
     */
    @Test
    fun `safety-exempt context rule exempts an app from the device budget and network blocking`() {
        val policy = policy(
            deviceBudget = WeeklyBudget.flat(1.hours),
            contextRules = listOf(
                ContextRule(
                    id = "exempt-homework",
                    name = "Exempt",
                    enabled = true,
                    condition = Condition.Always,
                    effect = RuleEffect.SafetyExempt,
                    target = RuleTarget.Apps(setOf(HOMEWORK)),
                ),
            ),
        ).copy(networkBlockedPackages = setOf(HOMEWORK, YOUTUBE))

        val result = PolicyEngine.evaluate(
            policy = policy,
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21", deviceTotal = 2.hours),
            now = at("2026-08-21", "18:00"),
        )

        assertNull(result.suspend[HOMEWORK])
        assertTrue(result.suspend.containsKey(YOUTUBE))
        assertTrue("an ordinary blocked app stays network-blocked", YOUTUBE in result.networkBlocked)
        assertTrue("the safety-exempt app must not be", HOMEWORK !in result.networkBlocked)
    }

    /**
     * [RuleEffect.AlwaysBlock] exists for exactly this: an [RuleEffect.Allow]
     * rule that would otherwise free the app must not, while a
     * [RuleEffect.SafetyExempt] rule still can — the one tier the override
     * itself cannot beat.
     */
    @Test
    fun `an always-block override survives an allow rule, but not a safety-exempt one`() {
        val policy = policy(
            contextRules = listOf(
                ContextRule(
                    id = "allow-everything",
                    name = "Allow",
                    enabled = true,
                    condition = Condition.Always,
                    effect = RuleEffect.Allow,
                    target = RuleTarget.Everything,
                ),
                ContextRule(
                    id = "always-block-game",
                    name = "Override",
                    enabled = true,
                    condition = Condition.Always,
                    effect = RuleEffect.AlwaysBlock(),
                    target = RuleTarget.Apps(setOf(GAME)),
                ),
            ),
        )

        val result = PolicyEngine.evaluate(
            policy = policy,
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21"),
            now = at("2026-08-21", "18:00"),
        )

        assertTrue("the override must survive the allow rule", result.suspend.containsKey(GAME))
        assertNull("an app the override does not name stays freed by the allow", result.suspend[HOMEWORK])

        val exempt = PolicyEngine.evaluate(
            policy = policy.copy(
                contextRules = policy.contextRules + ContextRule(
                    id = "exempt-game",
                    name = "Exempt",
                    enabled = true,
                    condition = Condition.Always,
                    effect = RuleEffect.SafetyExempt,
                    target = RuleTarget.Apps(setOf(GAME)),
                ),
            ),
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21"),
            now = at("2026-08-21", "18:00"),
        )

        assertNull("only a safety-exempt rule can free an app from the override", exempt.suspend[GAME])
    }

    // ---------- device budget ----------

    /**
     * [UsageSnapshot.deviceTotal]'s own doc comment says a safety-exempt
     * app's time does not count against the device budget — but usage is
     * collected against a static [Policy] alone, before any context rule is
     * resolved, so it can only apply that for [Policy.isSafetyExempt].
     * Whatever [RuleEffect.SafetyExempt] exempts contextually has to be
     * subtracted here, the one place both the usage and the context outcome
     * already exist together, or a long homework-app session would silently
     * eat into everyone else's device budget despite the app itself never
     * being restricted.
     */
    @Test
    fun `a context-exempt app's own time does not count against the device budget`() {
        val policy = policy(
            deviceBudget = WeeklyBudget.flat(1.hours),
            contextRules = listOf(
                ContextRule(
                    id = "exempt-homework",
                    name = "Exempt",
                    enabled = true,
                    condition = Condition.Always,
                    effect = RuleEffect.SafetyExempt,
                    target = RuleTarget.Apps(setOf(HOMEWORK)),
                ),
            ),
        )

        val result = PolicyEngine.evaluate(
            policy = policy,
            installedPackages = ALL_PACKAGES,
            // 50 minutes on the exempt app, 10 on everything else: without
            // subtracting the exempt app's share, the device total (1 hour)
            // would exhaust a 1-hour budget outright.
            usage = usage(
                "2026-08-21",
                deviceTotal = 1.hours,
                byPackage = mapOf(HOMEWORK to 50.minutes, YOUTUBE to 10.minutes),
            ),
            now = at("2026-08-21", "18:00"),
        )

        assertEquals(10.minutes, result.deviceBudget.used)
        assertEquals(BudgetLevel.NORMAL, result.deviceBudget.level)
        assertNull("the device budget is nowhere near exhausted", result.suspend[YOUTUBE])
    }

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

    // ---------- allowance rules (#102) ----------

    private fun allowanceRule(minutes: Int) = ContextRule(
        id = "allowance-rule",
        name = "Allowance rule",
        enabled = true,
        condition = Condition.AllowanceSpent(minutes),
        effect = RuleEffect.Block(),
        target = RuleTarget.Everything,
    )

    @Test
    fun `an allowance rule blocks everything the same way the dedicated device budget does`() {
        val now = at("2026-08-21", "18:00")

        fun outcome(rules: List<ContextRule>, usedMinutes: Long) = PolicyEngine.evaluate(
            policy = policy(contextRules = rules),
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21", deviceTotal = usedMinutes.minutes),
            now = now,
        )

        val dedicated = PolicyEngine.evaluate(
            policy = policy(deviceBudget = WeeklyBudget.flat(60.minutes)),
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21", deviceTotal = 60.minutes),
            now = now,
        )
        val viaRule = outcome(listOf(allowanceRule(60)), usedMinutes = 60)

        assertEquals(dedicated.suspend.keys, viaRule.suspend.keys)
        assertTrue(outcome(listOf(allowanceRule(60)), usedMinutes = 59).suspend.isEmpty())
    }

    @Test
    fun `a device grant delays an allowance rule exactly as it delays the dedicated budget`() {
        val now = at("2026-08-21", "18:00")

        val result = PolicyEngine.evaluate(
            policy = policy(contextRules = listOf(allowanceRule(60))),
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21", deviceTotal = 60.minutes),
            grants = listOf(
                grant(GrantTarget.Device, 20.minutes, from = now, until = at("2026-08-22", "04:00")),
            ),
            now = now,
        )

        // The grant nets against usage before the rule's condition is checked,
        // exactly as it does for the dedicated device budget (#102's "done when").
        assertTrue(result.suspend.isEmpty())
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

    /**
     * The complement of the bedtime case below, through the whole engine rather
     * than only the model predicate: an including window pauses its named apps
     * and leaves the rest alone.
     */
    @Test
    fun `an including window pauses only the apps it names`() {
        val result = PolicyEngine.evaluate(
            policy = policy(
                schedules = listOf(
                    bedtime(
                        startingOn = "2026-08-21",
                        allowed = setOf(GAME, YOUTUBE),
                        mode = ScheduleBlock.Mode.INCLUSIVE,
                    ),
                ),
            ),
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21"),
            now = at("2026-08-22", "02:00"),
        )

        assertEquals(setOf(GAME, YOUTUBE), result.suspend.keys)
        assertNull("an app the window does not name must carry on", result.suspend[HOMEWORK])
        assertTrue(result.suspend[GAME] is Reason.ContextRuleActive)
    }

    /**
     * The same set, read the other way, produces the exact opposite. This is the
     * inversion that makes the default matter: every schedule saved before modes
     * existed meant the excluding reading.
     */
    @Test
    fun `the same app set suspends opposite apps in the two modes`() {
        fun suspendedUnder(mode: ScheduleBlock.Mode) = PolicyEngine.evaluate(
            policy = policy(
                schedules = listOf(
                    bedtime(startingOn = "2026-08-21", allowed = setOf(CLOCK), mode = mode),
                ),
            ),
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21"),
            now = at("2026-08-22", "02:00"),
        ).suspend.keys

        assertEquals(setOf(YOUTUBE, GAME, HOMEWORK), suspendedUnder(ScheduleBlock.Mode.EXCLUSIVE))
        assertEquals(setOf(CLOCK), suspendedUnder(ScheduleBlock.Mode.INCLUSIVE))
    }

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
        assertTrue(reason is Reason.ContextRuleActive)
        assertEquals("Bedtime", (reason as Reason.ContextRuleActive).ruleName)
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
        assertEquals(emptyList<Any>(), result.activeContextRules)
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

        // An always-on manual block has no known end, unlike the bedtime it
        // outranks — that indefinite liftsAt is what makes it win, the same
        // "the one that lifts last" resolution the reason below tests
        // directly.
        val reason = result.suspend[YOUTUBE]
        assertTrue(reason is Reason.ContextRuleActive)
        assertNull(reason?.liftsAt)
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
        assertTrue("expected the schedule to win, got $reason", reason is Reason.ContextRuleActive)
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
        assertTrue(result.suspend[GAME] is Reason.ContextRuleActive)
    }

    /**
     * The rule reported for an app is the one lifting last, but hiding is
     * decided by every active block: a hidden rule that lifts sooner still
     * hides the app while it applies.
     */
    @Test
    fun `an app stays hidden when the rule reported for it is not the hidden one`() {
        val hiddenAtBedtime = ContextRule(
            id = "hidden-at-bedtime",
            name = "Hidden at bedtime",
            enabled = true,
            condition = Condition.DuringWindow(bedtime(startingOn = "2026-08-21").window),
            effect = RuleEffect.Block(hidden = true),
            target = RuleTarget.Apps(setOf(GAME)),
        )
        val result = PolicyEngine.evaluate(
            policy = policy(
                appRules = mapOf(GAME to AppRule.Blocked()),
                contextRules = listOf(hiddenAtBedtime),
            ),
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21"),
            now = at("2026-08-22", "02:00"),
        )

        assertEquals(setOf(GAME), result.hide)
        assertNull(result.suspend[GAME]?.liftsAt)
    }

    // ---------- deferral ----------

    private fun budgetSpent(deviceState: DeviceState) = PolicyEngine.evaluate(
        policy = policy(deviceBudget = WeeklyBudget.flat(1.hours)),
        installedPackages = ALL_PACKAGES,
        usage = usage("2026-08-21", deviceTotal = 2.hours),
        deviceState = deviceState,
        now = at("2026-08-21", "18:00"),
    )

    @Test
    fun `a live route defers that app's suspension rather than cancelling it`() {
        val result = budgetSpent(DeviceState(navigatingPackage = YOUTUBE))

        assertEquals(setOf(YOUTUBE), result.deferred.keys)
        assertTrue(result.reasonFor(YOUTUBE) is Reason.DeviceBudgetExhausted)
    }

    /**
     * The point of per-app deferral. Sparing the map must not spare the game:
     * a blanket lift would make starting a route a way round every other rule.
     */
    @Test
    fun `deferring one app leaves every other suspension standing`() {
        val result = budgetSpent(DeviceState(navigatingPackage = YOUTUBE))

        assertEquals(setOf(CLOCK, GAME, HOMEWORK), result.suspend.keys)
    }

    /** Nothing to hold back, so nothing is recorded as held back. */
    @Test
    fun `navigating in an app that is not suspended defers nothing`() {
        val result = PolicyEngine.evaluate(
            policy = policy(),
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21"),
            deviceState = DeviceState(navigatingPackage = YOUTUBE),
            now = at("2026-08-21", "18:00"),
        )

        assertEquals(emptyMap<Any, Any>(), result.deferred)
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
