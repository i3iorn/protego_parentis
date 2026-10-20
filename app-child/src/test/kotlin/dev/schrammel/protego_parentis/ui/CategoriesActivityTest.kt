package dev.schrammel.protego_parentis.ui

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.schrammel.protego_parentis.model.CategoryId
import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.model.RuleEffect
import dev.schrammel.protego_parentis.model.RuleTarget
import dev.schrammel.protego_parentis.model.WeeklyBudget
import dev.schrammel.protego_parentis.rules.ContextRuleStore
import dev.schrammel.protego_parentis.rules.LocalPolicyStore
import dev.schrammel.protego_parentis.rules.StoredCategory
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.LocalDeviceId
import dev.schrammel.protego_parentis.security.ParentSession
import dev.schrammel.protego_parentis.security.RoleStore
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.time.Duration.Companion.minutes

/**
 * The category list, which is the one screen that shows a category's limit.
 *
 * A category's limit is a [ContextRule] (a `SetBudget` aimed at the
 * category), not a field on the category: the editor writes the rule and
 * leaves the stored field at zero, because the rule is what the policy engine
 * reads. A list that read the field would therefore show every category as
 * unlimited, and a limit set a moment ago would look as though it had not
 * stuck.
 */
@RunWith(RobolectricTestRunner::class)
class CategoriesActivityTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        RoleStore(context).choose(DeviceRole.CHILD)
        LocalDeviceId.useInTests("THISPHONE")
        ParentSession.unlock()
    }

    @After
    fun tearDown() {
        LocalDeviceId.useInTests(null)
        ParentSession.lock()
    }

    private fun activity() = Robolectric.buildActivity(
        CategoriesActivity::class.java,
        Intent(context, CategoriesActivity::class.java),
    ).setup().get()

    @Test
    fun `a category's limit is shown even though the limit lives in a rule`() {
        // The shape the editor leaves behind: the category itself carries no
        // limit, and the rule carries the whole allowance.
        LocalPolicyStore(context).categories = listOf(
            StoredCategory(id = "games-id", name = "Games", limitMinutes = 0, packages = emptySet()),
        )
        ContextRuleStore(context).upsert(
            ContextRule(
                id = "migrated-category-budget-games-id",
                name = "Games",
                enabled = true,
                condition = Condition.Always,
                effect = RuleEffect.SetBudget(WeeklyBudget.flat(60.minutes)),
                target = RuleTarget.Categories(setOf(CategoryId("games-id"))),
            ),
        )

        val texts = textsIn(activity())

        assertTrue(
            "the category's limit must be shown: $texts",
            texts.any { it.contains("1h") },
        )
        assertFalse(
            "a category with a rule must not read as unlimited: $texts",
            texts.any { it.contains("No limit") },
        )
    }

    @Test
    fun `a category with no limit at all still reads as unlimited`() {
        LocalPolicyStore(context).categories = listOf(
            StoredCategory(id = "games-id", name = "Games", limitMinutes = 0, packages = emptySet()),
        )

        val texts = textsIn(activity())

        assertTrue(
            "a category with no limit must say so: $texts",
            texts.any { it.contains("No limit") },
        )
    }

    @Test
    fun `a category whose limit is still an old-format field keeps showing it`() {
        // A category no rule covers yet (a phone mid-upgrade, or one still
        // replicating an old-format edit) keeps its stored limit, and the list
        // must not lose it.
        LocalPolicyStore(context).categories = listOf(
            StoredCategory(id = "games-id", name = "Games", limitMinutes = 45, packages = emptySet()),
        )

        val texts = textsIn(activity())

        assertTrue(
            "an un-migrated category must keep its stored limit: $texts",
            texts.any { it.contains("45m") },
        )
    }

    private fun textsIn(activity: CategoriesActivity): List<String> = buildList {
        fun walk(view: View) {
            if (view is TextView && view.text.isNotEmpty()) add(view.text.toString())
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
        }
        walk(activity.window.decorView)
    }
}
