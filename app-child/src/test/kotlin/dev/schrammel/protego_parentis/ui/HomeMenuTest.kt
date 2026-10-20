package dev.schrammel.protego_parentis.ui

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.schrammel.protego_parentis.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowDialog

/**
 * The home screen's menu.
 */
@RunWith(RobolectricTestRunner::class)
class HomeMenuTest {

    private val activity: Activity =
        Robolectric.buildActivity(Activity::class.java).setup().get()

    @Test
    fun `choosing a row dismisses the menu`() {
        // The menu has to be gone before the screen behind it opens. Left
        // standing, it floats over the screen the reader just chose - and back
        // from there dismisses the menu again instead of returning to the
        // dashboard they came from.
        var opened: HomeDestination? = null
        activity.homeMenuDialog(
            listOf(
                HomeSection(
                    label = null,
                    rows = listOf(
                        HomeRow(
                            R.string.home_open_week,
                            R.string.home_open_week_detail,
                            HomeDestination.WEEK,
                        ),
                    ),
                ),
            ),
        ) { destination -> opened = destination }

        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull("the menu must be showing", dialog)
        assertTrue("the menu must be showing", dialog.isShowing)

        clickRow(dialog, activity.getString(R.string.home_open_week))

        assertEquals("the row must open its destination", HomeDestination.WEEK, opened)
        assertFalse("the menu must be gone before the screen opens", dialog.isShowing)
    }

    @Test
    fun `the menu shows each row's title and its one-line description`() {
        activity.homeMenuDialog(
            listOf(
                HomeSection(
                    label = null,
                    rows = listOf(
                        HomeRow(
                            R.string.home_open_week,
                            R.string.home_open_week_detail,
                            HomeDestination.WEEK,
                        ),
                    ),
                ),
            ),
        ) { }

        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull("the menu must be showing", dialog)
        val texts = textsIn(dialog.window!!.decorView)
        assertTrue(
            "the row must carry its title: $texts",
            texts.any { it == activity.getString(R.string.home_open_week) },
        )
        assertTrue(
            "the row must carry its description: $texts",
            texts.any { it == activity.getString(R.string.home_open_week_detail) },
        )
    }

    /** Clicks the row carrying [title], rather than the text inside it. */
    private fun clickRow(dialog: android.app.Dialog, title: String) {
        val text = requireNotNull(findText(dialog.window!!.decorView, title)) {
            "the menu must carry a row titled \"$title\""
        }
        // The title sits in a column inside the row; the row is the first
        // horizontal ancestor above it, and the row is what carries the tap.
        var row: View = text
        while (row !is android.widget.LinearLayout ||
            row.orientation != android.widget.LinearLayout.HORIZONTAL
        ) {
            row = requireNotNull(row.parent as? View) { "the row must be above the title" }
        }
        row.performClick()
    }

    private fun findText(view: View, text: String): TextView? {
        if (view is TextView && view.text.toString() == text) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findText(view.getChildAt(i), text)?.let { return it }
        }
        return null
    }

    private fun textsIn(view: View): List<String> = buildList {
        if (view is TextView && view.text.isNotEmpty()) add(view.text.toString())
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) addAll(textsIn(view.getChildAt(i)))
        }
    }
}
