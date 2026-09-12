package dev.schrammel.protego_parentis.ui

import android.app.Activity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * [group] (and, directly, [addCollapsibleRows]) hiding whatever a list has past
 * ten rows behind a "Show N more" line, and revealing it on tap — the mechanism
 * behind every long list of apps, devices or history entries in the app.
 */
@RunWith(RobolectricTestRunner::class)
class ShowMoreRowsTest {

    private val activity: Activity =
        Robolectric.buildActivity(Activity::class.java).setup().get()

    private fun row(label: String) = TextView(activity).apply { text = label }

    private fun texts(container: LinearLayout): List<String> =
        (0 until container.childCount)
            .map { container.getChildAt(it) }
            .filterIsInstance<TextView>()
            .map { it.text.toString() }

    @Test
    fun `ten rows or fewer are all shown, with no show-more row`() {
        val rows = (1..10).map { row("row $it") }

        val shown = activity.group(rows)

        assertEquals((1..10).map { "row $it" }, texts(shown))
    }

    @Test
    fun `past ten rows, the rest hide behind a show-more row naming the count`() {
        val rows = (1..12).map { row("row $it") }

        val shown = activity.group(rows)

        assertEquals((1..10).map { "row $it" } + "Show 2 more", texts(shown))
    }

    @Test
    fun `tapping show-more reveals every remaining row, in order, and removes itself`() {
        val rows = (1..12).map { row("row $it") }
        val shown = activity.group(rows)
        val moreRow = (0 until shown.childCount).map(shown::getChildAt).last { it is TextView && it.isClickable }

        moreRow.performClick()

        assertEquals((1..12).map { "row $it" }, texts(shown))
        assertFalse("show-more row" , texts(shown).any { it.startsWith("Show ") })
    }

    @Test
    fun `exactly one divider separates the last visible row from what show-more reveals`() {
        val rows = (1..12).map { row("row $it") }
        val shown = activity.group(rows)
        val moreRow = (0 until shown.childCount).map(shown::getChildAt).last { it is TextView && it.isClickable }

        moreRow.performClick()

        // 12 rows in a fully expanded group should sit behind 11 dividers, one
        // between each pair — never two in a row at the seam show-more used to sit on.
        val dividers = (0 until shown.childCount).count { shown.getChildAt(it) !is TextView }
        assertEquals(11, dividers)
    }

    @Test
    fun `a single hidden row uses the singular count`() {
        val rows = (1..11).map { row("row $it") }

        val shown = activity.group(rows)

        assertTrue(texts(shown).last() == "Show 1 more")
    }
}
