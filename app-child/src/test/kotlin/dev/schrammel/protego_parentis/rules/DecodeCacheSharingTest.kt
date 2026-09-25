package dev.schrammel.protego_parentis.rules

import android.content.Context
import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.model.RuleEffect
import dev.schrammel.protego_parentis.model.RuleTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [DecodeCache] only pays off if it is shared: screens and the per-poll
 * replication classes each construct their own store, and a cache held per
 * instance was cold for all of them. Sharing is only safe because the cache
 * is keyed on the stored string, so a write through any instance is seen by
 * every other.
 */
@RunWith(RobolectricTestRunner::class)
class DecodeCacheSharingTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun rule(id: String) = ContextRule(
        id = id,
        name = "Rule $id",
        enabled = true,
        condition = Condition.Always,
        effect = RuleEffect.Block(),
        target = RuleTarget.Everything,
    )

    @Test
    fun `two instances share one decode of an unchanged file`() {
        ContextRuleStore(context).rules = listOf(rule("a"))

        assertSame(ContextRuleStore(context).rules, ContextRuleStore(context).rules)
    }

    @Test
    fun `a write through one instance is seen by another that already read`() {
        val reader = ContextRuleStore(context)
        ContextRuleStore(context).rules = listOf(rule("a"))
        assertEquals(listOf("a"), reader.rules.map { it.id })

        ContextRuleStore(context).rules = listOf(rule("a"), rule("b"))

        assertEquals(listOf("a", "b"), reader.rules.map { it.id })
    }
}
