package dev.schrammel.protego_parentis.rules

import android.content.Context
import androidx.core.content.edit
import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.model.RuleAction
import dev.schrammel.protego_parentis.model.RuleEffect
import dev.schrammel.protego_parentis.model.RuleTarget
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** V1 rules keep their old on-disk shape; V2 rules are written so a V1 build drops them. */
@RunWith(RobolectricTestRunner::class)
class ContextRuleStoreFormatTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("context_rules", Context.MODE_PRIVATE)

    private fun store() = ContextRuleStore(context)

    private fun storedRule(): JSONObject = JSONArray(prefs.getString("rules", null)).getJSONObject(0)

    @After
    fun tearDown() {
        store().rules = emptyList()
    }

    @Test
    fun `a one-action rule is stored exactly as before`() {
        store().rules = listOf(ContextRule("r", "Rule", true, Condition.Always, RuleEffect.Block(), RuleTarget.Everything))

        val json = storedRule()
        assertTrue(json.has("effect"))
        assertTrue(json.has("target"))
        assertFalse(json.has("format"))
        assertFalse(json.has("actions"))
    }

    @Test
    fun `a v2 rule round-trips and has no v1 effect or target`() {
        val rule = ContextRule(
            id = "r",
            name = "Rule",
            enabled = true,
            condition = Condition.OnWifi("School"),
            actions = listOf(
                RuleAction(RuleEffect.Block(), RuleTarget.Everything),
                RuleAction(RuleEffect.SafetyExempt, RuleTarget.Everything),
            ),
            exceptWhen = Condition.BatteryBelow(15),
        )

        store().rules = listOf(rule)

        assertEquals(listOf(rule), store().rules)
        assertFalse(storedRule().has("effect"))
        assertFalse(storedRule().has("target"))
    }

    @Test
    fun `a rule whose condition cannot be read is dropped, not made Always`() {
        val raw = JSONArray().put(
            JSONObject()
                .put("id", "r")
                .put("name", "Rule")
                .put("condition", JSONObject().put("kind", "fromTheFuture"))
                .put("effect", JSONObject().put("kind", "allow"))
                .put("target", JSONObject().put("kind", "everything")),
        )
        prefs.edit(commit = true) { putString("rules", raw.toString()) }

        assertTrue(store().rules.isEmpty())
    }
}
