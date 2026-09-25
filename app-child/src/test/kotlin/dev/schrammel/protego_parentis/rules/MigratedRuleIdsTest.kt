package dev.schrammel.protego_parentis.rules

import android.content.Context
import dev.schrammel.protego_parentis.model.PackageName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The migration writes these ids and the screens find and parse them again;
 * the property that matters is that the two ends agree, so these go through
 * what [RuleMigration] actually produces rather than only through
 * [MigratedRuleIds] itself.
 */
@RunWith(RobolectricTestRunner::class)
class MigratedRuleIdsTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val pkg = PackageName("com.example.game")

    @Test
    fun `a migrated block rule is found at the id the screens look for and parses back to its app`() {
        val id = RuleMigration.appBlockRules(context, setOf(pkg.value), emptySet()).single().id

        assertEquals(MigratedRuleIds.appBlock(pkg), id)
        assertEquals(pkg, MigratedRuleIds.singleAppOf(id))
    }

    @Test
    fun `a migrated app budget rule is found at the id the screens look for and parses back to its app`() {
        val id = RuleMigration.appBudgetRules(context, mapOf(pkg.value to 30)).single().id

        assertEquals(MigratedRuleIds.appBudget(pkg), id)
        assertEquals(pkg, MigratedRuleIds.singleAppOf(id))
    }

    @Test
    fun `ids that don't name a single app parse to nothing`() {
        assertNull(MigratedRuleIds.singleAppOf(MigratedRuleIds.categoryBudget("games")))
        assertNull(MigratedRuleIds.singleAppOf(MigratedRuleIds.exempt(pkg)))
        assertNull(MigratedRuleIds.singleAppOf("a-parent-made-rule"))
    }
}
