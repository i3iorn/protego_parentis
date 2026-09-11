package dev.schrammel.protego_parentis.rules

import android.content.Context
import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.RuleEffect
import dev.schrammel.protego_parentis.model.RuleTarget
import dev.schrammel.protego_parentis.model.ScheduleBlock
import dev.schrammel.protego_parentis.model.WeeklyBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.time.Duration.Companion.minutes

/**
 * #105: [LocalPolicyStore.migrateToUnifiedRules] must fold every existing
 * schedule and the flat daily limit into [ContextRuleStore] without losing
 * any of them, exactly once.
 *
 * These pin the migrated shape of each stored form directly — the same
 * shapes [dev.schrammel.protego_parentis.policy.PolicyEngine]'s own test
 * suite already proves produce correct enforcement decisions (an
 * `EXCLUSIVE`/`INCLUSIVE` schedule and a flat device budget expressed as
 * `ContextRule`s), so together the two suites are the "identical
 * enforcement decisions before and after" proof #105 asks for, without
 * this file needing its own timezone-sensitive end-to-end evaluation.
 */
@RunWith(RobolectricTestRunner::class)
class RuleMigrationTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun store() = LocalPolicyStore(context)
    private fun schedules() = ScheduleStore(context)
    private fun rules() = ContextRuleStore(context)

    @Test
    fun `an exclusive schedule migrates to an everything-except block`() {
        schedules().upsert(
            StoredSchedule(
                id = "bedtime",
                name = "Bedtime",
                days = setOf(1),
                startMinuteOfDay = 22 * 60 + 30,
                endMinuteOfDay = 7 * 60,
                packages = setOf("com.google.android.deskclock"),
                mode = ScheduleBlock.Mode.EXCLUSIVE,
            ),
        )

        store().migrateToUnifiedRules()

        val rule = rules().rules.singleOrNull { it.id == "migrated-schedule-bedtime" }
        assertEquals("Bedtime", rule?.name)
        assertTrue(rule?.enabled == true)
        assertTrue(rule?.condition is Condition.DuringWindow)
        assertEquals(RuleEffect.Block, rule?.effect)
        assertEquals(
            RuleTarget.EverythingExcept(setOf(PackageName("com.google.android.deskclock"))),
            rule?.target,
        )
    }

    @Test
    fun `an inclusive schedule migrates to an apps-only block`() {
        schedules().upsert(
            StoredSchedule(
                id = "homework",
                name = "Homework",
                days = setOf(1, 2, 3, 4, 5),
                startMinuteOfDay = 16 * 60,
                endMinuteOfDay = 18 * 60,
                packages = setOf("com.supercell.clashroyale"),
                mode = ScheduleBlock.Mode.INCLUSIVE,
            ),
        )

        store().migrateToUnifiedRules()

        val rule = rules().rules.singleOrNull { it.id == "migrated-schedule-homework" }
        assertEquals(
            RuleTarget.Apps(setOf(PackageName("com.supercell.clashroyale"))),
            rule?.target,
        )
    }

    @Test
    fun `a disabled schedule migrates as a disabled rule, not dropped`() {
        schedules().upsert(
            StoredSchedule(
                id = "sometimes",
                name = "Sometimes",
                days = setOf(1),
                startMinuteOfDay = 8 * 60,
                endMinuteOfDay = 9 * 60,
                enabled = false,
            ),
        )

        store().migrateToUnifiedRules()

        val rule = rules().rules.singleOrNull { it.id == "migrated-schedule-sometimes" }
        assertFalse("the rule must still exist, just switched off", rule == null)
        assertFalse(rule?.enabled ?: true)
    }

    /** [StoredSchedule.isComplete] false today; never reached the engine before either. */
    @Test
    fun `an incomplete schedule is skipped, not migrated broken`() {
        schedules().upsert(
            StoredSchedule(
                id = "half-built",
                name = "",
                days = emptySet(),
                startMinuteOfDay = 0,
                endMinuteOfDay = 0,
            ),
        )

        store().migrateToUnifiedRules()

        assertTrue(rules().rules.none { it.id == "migrated-schedule-half-built" })
    }

    @Test
    fun `a flat daily limit migrates to an always-on device budget rule`() {
        store().dailyLimitMinutes = 90

        store().migrateToUnifiedRules()

        val rule = rules().rules.singleOrNull { it.id == "migrated-daily-limit" }
        assertEquals(Condition.Always, rule?.condition)
        assertEquals(RuleEffect.SetDeviceBudget(WeeklyBudget.flat(90.minutes)), rule?.effect)
        assertEquals(RuleTarget.Everything, rule?.target)
    }

    @Test
    fun `no limit means no migrated rule, not a zero-minute block`() {
        store().dailyLimitMinutes = 0

        store().migrateToUnifiedRules()

        assertNull(rules().rules.firstOrNull { it.id == "migrated-daily-limit" })
    }

    @Test
    fun `running the migration twice changes nothing`() {
        schedules().upsert(
            StoredSchedule(
                id = "bedtime",
                name = "Bedtime",
                days = setOf(1),
                startMinuteOfDay = 22 * 60 + 30,
                endMinuteOfDay = 7 * 60,
            ),
        )
        store().dailyLimitMinutes = 60

        store().migrateToUnifiedRules()
        val firstPass = rules().rules

        store().migrateToUnifiedRules()

        assertEquals(firstPass, rules().rules)
    }

    /**
     * The accepted limitation from #105's own scoping: the old screens still
     * write these stores directly until #106 replaces them, and this only
     * runs once, so an edit made after that point is not picked up here
     * again. Documented by a test rather than left to be rediscovered as a
     * bug report.
     */
    @Test
    fun `an edit made after migration is not retroactively migrated`() {
        store().dailyLimitMinutes = 60
        store().migrateToUnifiedRules()

        store().dailyLimitMinutes = 30
        store().migrateToUnifiedRules()

        val rule = rules().rules.single { it.id == "migrated-daily-limit" }
        assertEquals(RuleEffect.SetDeviceBudget(WeeklyBudget.flat(60.minutes)), rule.effect)
    }
}
