package dev.schrammel.protego_parentis.ui

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.model.BudgetLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * The one shared "how much time is left" card the child's dashboard and a
 * parent's synced summary both build — see [budgetSummaryCard].
 */
@RunWith(RobolectricTestRunner::class)
class BudgetSummaryTest {

    private val activity: Activity =
        Robolectric.buildActivity(Activity::class.java).setup().get()

    private fun childrenOf(container: ViewGroup): List<View> =
        (0 until container.childCount).map { container.getChildAt(it) }

    private fun textViews(container: ViewGroup): List<TextView> =
        childrenOf(container).filterIsInstance<TextView>()

    @Test
    fun `an unlimited budget shows no bar`() {
        val card = activity.budgetSummaryCard("No limit", BudgetLevel.NORMAL, progress = null)

        assertTrue(childrenOf(card).none { it is ProgressBar })
    }

    @Test
    fun `a limited budget shows a bar filled to the percentage`() {
        val card = activity.budgetSummaryCard("30 minutes left", BudgetLevel.NOTICE, progress = 40)

        val bar = childrenOf(card).filterIsInstance<ProgressBar>().single()
        assertEquals(40, bar.progress)
        assertEquals(View.VISIBLE, bar.visibility)
    }

    @Test
    fun `the headline takes the level's own colour`() {
        val card = activity.budgetSummaryCard("Used up", BudgetLevel.EXHAUSTED, progress = 100)

        val headline = textViews(card).first()
        assertEquals(
            ContextCompat.getColor(activity, R.color.state_critical),
            headline.currentTextColor,
        )
    }

    @Test
    fun `the note is inside the card only when supplied`() {
        val withNote = activity.budgetSummaryCard(
            "No limit",
            BudgetLevel.NORMAL,
            progress = null,
            note = "Monitor only",
        )
        assertTrue(textViews(withNote).any { it.text == "Monitor only" })

        val without = activity.budgetSummaryCard("No limit", BudgetLevel.NORMAL, progress = null)
        assertTrue(textViews(without).none { it.text == "Monitor only" })
    }
}
