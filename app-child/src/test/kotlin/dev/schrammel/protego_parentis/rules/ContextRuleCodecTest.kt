package dev.schrammel.protego_parentis.rules

import dev.schrammel.protego_parentis.model.CategoryId
import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.DeviceTag
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.PlaceId
import dev.schrammel.protego_parentis.model.RuleEffect
import dev.schrammel.protego_parentis.model.RuleTarget
import dev.schrammel.protego_parentis.model.WeeklyBudget
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
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
            Condition.AllOf(listOf(Condition.OnWifi("HomeNet"), Condition.BatteryBelow(20))),
            Condition.AnyOf(listOf(Condition.OnWifi("HomeNet"), Condition.AtPlace(PlaceId("place-1")))),
            Condition.Not(Condition.OnWifi("HomeNet")),
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
