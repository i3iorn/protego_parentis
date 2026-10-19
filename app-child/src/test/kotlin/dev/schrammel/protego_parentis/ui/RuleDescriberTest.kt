package dev.schrammel.protego_parentis.ui

import android.app.Activity
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.glucose.GlucoseUnit
import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.model.PlaceId
import dev.schrammel.protego_parentis.model.RuleAction
import dev.schrammel.protego_parentis.model.RuleEffect
import dev.schrammel.protego_parentis.model.RuleTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.minutes

/**
 * The rule card's parts ([RuleDescriber.parts], rules-UX phase C): one per
 * shape of rule, so a card drawn from these parts cannot drift from the
 * sentence the platform does not concatenate otherwise. Needs Robolectric for
 * the activity's strings, the way [ContextRuleEditActivityTest] does.
 */
@RunWith(RobolectricTestRunner::class)
class RuleDescriberTest {

    private fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    private fun describer(
        activity: Activity,
        placeNames: Map<String, String> = emptyMap(),
        glucoseUnit: GlucoseUnit? = null,
    ): RuleDescriber = RuleDescriber(
        activity = activity,
        placeName = { placeNames[it.value] },
        categoryName = { null },
        glucoseUnit = glucoseUnit,
    )

    private fun window(days: Set<java.time.DayOfWeek> = setOf(java.time.DayOfWeek.MONDAY)) = Condition.DuringWindow(
        dev.schrammel.protego_parentis.model.RecurringWindow(
            days = days,
            start = java.time.LocalTime.of(8, 30),
            end = java.time.LocalTime.of(15, 0),
        ),
    )

    @Test
    fun `a schedule rule reads when first, then second`() {
        val activity = activity()
        val rule = ContextRule(
            id = "school",
            name = "School",
            enabled = true,
            condition = window(),
            effect = RuleEffect.Block(),
            target = RuleTarget.Everything,
        )

        val parts = describer(activity).parts(rule)

        assertEquals(RuleKind.WINDOW, parts.kind)
        assertEquals(1, parts.whenLines.size)
        assertTrue(parts.whenLines.single(), parts.whenLines.single().contains("8:30"))
        assertEquals(
            listOf(
                "${activity.getString(R.string.context_effect_block)} ${activity.getString(R.string.context_target_everything)}",
            ),
            parts.thenLines,
        )
        assertEquals(null, parts.except)
    }

    @Test
    fun `a negated Wi-Fi rule stays about Wi-Fi but reads negated`() {
        val activity = activity()
        val rule = ContextRule(
            id = "not-school",
            name = "Not school",
            enabled = true,
            condition = Condition.Not(Condition.OnWifi("School")),
            effect = RuleEffect.Block(),
            target = RuleTarget.Everything,
        )

        val parts = describer(activity).parts(rule)

        assertEquals(RuleKind.WIFI, parts.kind)
        assertTrue(parts.whenLines.single(), parts.whenLines.single().contains("School"))
        assertTrue("the negation is part of the row", parts.whenLines.single() != activity.getString(R.string.context_when_wifi, "School"))
    }

    @Test
    fun `a place rule is named by the saved place`() {
        val activity = activity()
        val rule = ContextRule(
            id = "grandma",
            name = "Grandma",
            enabled = true,
            condition = Condition.AtPlace(PlaceId("p1")),
            effect = RuleEffect.Allow,
            target = RuleTarget.Everything,
        )

        val parts = describer(activity, placeNames = mapOf("p1" to "Mormor")).parts(rule)

        assertEquals(RuleKind.PLACE, parts.kind)
        assertTrue(parts.whenLines.single(), parts.whenLines.single().contains("Mormor"))
    }

    @Test
    fun `the kind is the first leaf's family, mixing rules included`() {
        val activity = activity()
        fun kindOf(condition: Condition) = describer(activity).parts(
            ContextRule(
                id = "x",
                name = "Mixed",
                enabled = true,
                condition = condition,
                effect = RuleEffect.Block(),
                target = RuleTarget.Everything,
            ),
        ).kind

        assertEquals(RuleKind.WINDOW, kindOf(Condition.AllOf(listOf(window(), Condition.OnWifi("School")))))
        assertEquals(RuleKind.WIFI, kindOf(Condition.AnyOf(listOf(Condition.OnWifi("School"), window()))))
        assertEquals(RuleKind.BATTERY, kindOf(Condition.BatteryBelow(20)))
        assertEquals(RuleKind.ALWAYS, kindOf(Condition.Always))
        assertEquals(RuleKind.GLUCOSE, kindOf(Condition.GlucoseBelow(72)))
        assertEquals(RuleKind.DEVICE, kindOf(Condition.NearDevice(dev.schrammel.protego_parentis.model.DeviceTag("AB:CD"))))
    }

    @Test
    fun `every action becomes its own then line`() {
        val activity = activity()
        val rule = ContextRule(
            id = "two",
            name = "Two actions",
            enabled = true,
            condition = Condition.Always,
            actions = listOf(
                RuleAction(RuleEffect.Block(), RuleTarget.Everything),
                RuleAction(RuleEffect.Allow, RuleTarget.Apps(setOf(dev.schrammel.protego_parentis.model.PackageName("com.example.app")))),
            ),
        )

        val parts = describer(activity).parts(rule)

        assertEquals(2, parts.thenLines.size)
        assertEquals(
            "${activity.getString(R.string.context_effect_allow)} ${activity.resources.getQuantityString(R.plurals.rules_category_apps, 1, 1)}",
            parts.thenLines[1],
        )
    }

    @Test
    fun `an exception becomes its own except phrase`() {
        val activity = activity()
        val rule = ContextRule(
            id = "except",
            name = "Except low battery",
            enabled = true,
            condition = Condition.Always,
            actions = listOf(RuleAction(RuleEffect.Block(), RuleTarget.Everything)),
            exceptWhen = Condition.BatteryBelow(10),
        )

        val parts = describer(activity).parts(rule)

        assertTrue(parts.except ?: "", parts.except ?: "" != "always")
        assertTrue(parts.except ?: "", parts.except?.contains("10") == true)
        // The exception lives on its own line, not inside the "when" rows.
        assertEquals(1, parts.whenLines.size)
    }

    @Test
    fun `a deep tree keeps every leaf as a when line, for the card to trim`() {
        val activity = activity()
        val rule = ContextRule(
            id = "deep",
            name = "Deep",
            enabled = true,
            condition = Condition.AtLeast(
                2,
                listOf(
                    Condition.BatteryBelow(20),
                    Condition.AllowanceSpent(60),
                    window(),
                ),
            ),
            effect = RuleEffect.Block(),
            target = RuleTarget.Everything,
        )

        val parts = describer(activity).parts(rule)

        assertEquals(3, parts.whenLines.size)
        assertTrue(parts.whenLines[0].contains("20"))
        assertTrue(parts.whenLines[2].contains("8:30"))
    }

    @Test
    fun `a glucose rule reads with the family's unit`() {
        val activity = activity()
        val rule = ContextRule(
            id = "low",
            name = "Low",
            enabled = true,
            condition = Condition.GlucoseBelow(72),
            effect = RuleEffect.SafetyExempt,
            target = RuleTarget.Everything,
        )

        val parts = describer(activity, glucoseUnit = GlucoseUnit.MMOL_L).parts(rule)

        assertEquals(RuleKind.GLUCOSE, parts.kind)
        // The number is converted into the family's unit, not shown raw.
        val phrase = parts.whenLines.single()
        assertTrue(phrase, phrase.contains(GlucoseUnit.MMOL_L.format(72)))
    }

    @Test
    fun `a daily-limit-shaped rule sets a budget as its then line`() {
        val activity = activity()
        val rule = ContextRule(
            id = "limit",
            name = "Daily limit",
            enabled = true,
            condition = Condition.Always,
            effect = RuleEffect.SetBudget(dev.schrammel.protego_parentis.model.WeeklyBudget.flat(90.minutes)),
            target = RuleTarget.Everything,
        )

        val parts = describer(activity).parts(rule)

        assertTrue(parts.thenLines.single().contains(activity.getString(R.string.context_target_everything)))
    }
}
