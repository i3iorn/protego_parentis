package dev.schrammel.protego_parentis.rules

import dev.schrammel.protego_parentis.model.CategoryId
import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.DeviceTag
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.PlaceId
import dev.schrammel.protego_parentis.model.RecurringWindow
import dev.schrammel.protego_parentis.model.RuleEffect
import dev.schrammel.protego_parentis.model.RuleTarget
import dev.schrammel.protego_parentis.model.WeeklyBudget
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlin.time.Duration.Companion.minutes

/**
 * The wire format shared by [ContextRuleStore]'s local storage and
 * [dev.schrammel.protego_parentis.sync.PolicyReplication]'s replicated
 * registers — both go through the same encode/decode functions here.
 */
class ContextRuleCodecTest {

    @Test
    fun `every condition kind round-trips`() {
        val conditions = listOf(
            Condition.Always,
            Condition.OnWifi("HomeNet"),
            Condition.NearDevice(DeviceTag("watch-1")),
            Condition.AtPlace(PlaceId("place-1")),
            Condition.BatteryBelow(20),
            // #102, the daily-limit condition.
            Condition.AllowanceSpent(60),
            Condition.AllOf(listOf(Condition.OnWifi("HomeNet"), Condition.BatteryBelow(20))),
            Condition.AnyOf(listOf(Condition.OnWifi("HomeNet"), Condition.AtPlace(PlaceId("place-1")))),
            Condition.Not(Condition.OnWifi("HomeNet")),
            // #101, in all three shapes it comes in: a same-day window, one
            // crossing midnight, and one on alternating weeks.
            Condition.DuringWindow(
                RecurringWindow(
                    days = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
                    start = LocalTime.of(8, 0),
                    end = LocalTime.of(15, 0),
                ),
            ),
            Condition.DuringWindow(
                RecurringWindow(
                    days = setOf(DayOfWeek.SATURDAY),
                    start = LocalTime.of(22, 30),
                    end = LocalTime.of(7, 0),
                ),
            ),
            Condition.DuringWindow(
                RecurringWindow(
                    days = setOf(DayOfWeek.WEDNESDAY),
                    start = LocalTime.of(9, 15),
                    end = LocalTime.of(10, 45),
                    parityAnchor = LocalDate.of(2026, 8, 21),
                ),
            ),
        )

        conditions.forEach { condition ->
            assertEquals(condition, ContextRuleCodec.decodeCondition(ContextRuleCodec.encodeCondition(condition)))
        }
    }

    @Test
    fun `a nested condition round-trips through the string wrappers too`() {
        val condition = Condition.AllOf(
            listOf(
                Condition.AnyOf(listOf(Condition.OnWifi("HomeNet"), Condition.Not(Condition.BatteryBelow(15)))),
                Condition.AtPlace(PlaceId("place-1")),
            ),
        )

        val roundTripped = ContextRuleCodec.decodeConditionFromString(
            ContextRuleCodec.encodeConditionToString(condition),
        )

        assertEquals(condition, roundTripped)
    }

    @Test
    fun `a condition nested past the depth bound decodes to null instead of overflowing the stack`() {
        var condition: Condition = Condition.Always
        repeat(ContextRuleCodec.MAX_CONDITION_DEPTH + 2) { condition = Condition.Not(condition) }

        assertNull(ContextRuleCodec.decodeCondition(ContextRuleCodec.encodeCondition(condition)))
    }

    @Test
    fun `a condition at exactly the depth bound still decodes`() {
        var condition: Condition = Condition.Always
        repeat(ContextRuleCodec.MAX_CONDITION_DEPTH) { condition = Condition.Not(condition) }

        assertEquals(condition, ContextRuleCodec.decodeCondition(ContextRuleCodec.encodeCondition(condition)))
    }

    /**
     * #101: [RecurringWindow] refuses a window with no days or with equal
     * start and end. Stored JSON can be corrupt or hand-edited, and this runs
     * on the phone that has to keep working — so a window that cannot be built
     * drops its rule rather than taking the whole rule list down with it.
     */
    @Test
    fun `a malformed window decodes to null rather than throwing`() {
        val validDays = JSONArray(listOf(DayOfWeek.MONDAY.value))

        val noDays = JSONObject()
            .put("kind", "window")
            .put("days", JSONArray(emptyList<Int>()))
            .put("startMinute", 8 * 60)
            .put("endMinute", 15 * 60)
        val sameStartAndEnd = JSONObject()
            .put("kind", "window")
            .put("days", validDays)
            .put("startMinute", 8 * 60)
            .put("endMinute", 8 * 60)
        val startOutOfRange = JSONObject()
            .put("kind", "window")
            .put("days", validDays)
            .put("startMinute", 24 * 60)
            .put("endMinute", 15 * 60)
        val noDaysField = JSONObject()
            .put("kind", "window")
            .put("startMinute", 8 * 60)
            .put("endMinute", 15 * 60)

        listOf(noDays, sameStartAndEnd, startOutOfRange, noDaysField).forEach { json ->
            assertNull(ContextRuleCodec.decodeCondition(json))
        }
    }

    @Test
    fun `an unrecognised condition kind decodes to null rather than throwing`() {
        val json = JSONObject().apply { put("kind", "fromTheFuture") }

        assertNull(ContextRuleCodec.decodeCondition(json))
    }

    @Test
    fun `every effect kind round-trips`() {
        val effects = listOf(
            RuleEffect.Block,
            RuleEffect.Allow,
            RuleEffect.SafetyExempt,
            RuleEffect.SetDeviceBudget(WeeklyBudget.flat(45.minutes)),
        )

        effects.forEach { effect ->
            assertEquals(effect, ContextRuleCodec.decodeEffect(ContextRuleCodec.encodeEffect(effect)))
            assertEquals(
                effect,
                ContextRuleCodec.decodeEffectFromString(ContextRuleCodec.encodeEffectToString(effect)),
            )
        }
    }

    @Test
    fun `an unrecognised effect kind decodes to null rather than throwing`() {
        val json = JSONObject().apply { put("kind", "fromTheFuture") }

        assertNull(ContextRuleCodec.decodeEffect(json))
    }

    @Test
    fun `every target kind round-trips`() {
        val targets = listOf(
            RuleTarget.Everything,
            RuleTarget.Apps(setOf(PackageName("com.example.app"))),
            RuleTarget.Categories(setOf(CategoryId("games"))),
            RuleTarget.Mixed(
                packages = setOf(PackageName("com.example.app")),
                categories = setOf(CategoryId("games")),
            ),
            RuleTarget.EverythingExcept(setOf(PackageName("com.android.deskclock"))),
        )

        targets.forEach { target ->
            assertEquals(target, ContextRuleCodec.decodeTarget(ContextRuleCodec.encodeTarget(target)))
            assertEquals(
                target,
                ContextRuleCodec.decodeTargetFromString(ContextRuleCodec.encodeTargetToString(target)),
            )
        }
    }

    @Test
    fun `an unrecognised target kind decodes to null rather than throwing`() {
        val json = JSONObject().apply { put("kind", "fromTheFuture") }

        assertNull(ContextRuleCodec.decodeTarget(json))
    }

    @Test
    fun `malformed json from a string register decodes to null rather than throwing`() {
        assertNull(ContextRuleCodec.decodeConditionFromString("not json"))
        assertNull(ContextRuleCodec.decodeEffectFromString("not json"))
        assertNull(ContextRuleCodec.decodeTargetFromString("not json"))
        assertNull(ContextRuleCodec.decodeConditionFromString(null))
    }
}
