package dev.schrammel.protego_parentis.ui

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.schrammel.protego_parentis.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Not PIN-gated and not asynchronous, unlike almost every other screen in
 * this package — see the class doc comment for why. This just pins that
 * every worked example actually renders, so a typo in one of the
 * [dev.schrammel.protego_parentis.R.string] ids referenced from its example
 * table fails a test instead of shipping a blank card.
 */
@RunWith(RobolectricTestRunner::class)
class RulesTutorialActivityTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `every worked example renders its title`() {
        val activity = Robolectric.buildActivity(RulesTutorialActivity::class.java).setup().get()
        val texts = findAllText(activity.window.decorView)

        listOf(
            R.string.rules_tutorial_never_block_title,
            R.string.rules_tutorial_block_except_title,
            R.string.rules_tutorial_lockdown_title,
            R.string.rules_tutorial_weekend_budget_title,
            R.string.rules_tutorial_allof_title,
            R.string.rules_tutorial_anyof_title,
            R.string.rules_tutorial_safety_title,
        ).forEach { titleRes ->
            val title = context.getString(titleRes)
            assertTrue("expected \"$title\" to be rendered", texts.contains(title))
        }
    }

    private fun findAllText(view: View): List<String> {
        val matches = mutableListOf<String>()
        fun walk(v: View) {
            if (v is TextView) matches += v.text.toString()
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(view)
        return matches
    }
}
