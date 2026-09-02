package dev.schrammel.protego_parentis.ui

import android.app.Activity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * [group] and [card] draw the same surface, so they have to be spaced the same.
 * `group` carried no margin at all, which left it flush against whatever came
 * before it on every screen with no section header in between.
 */
@RunWith(RobolectricTestRunner::class)
class CardSpacingTest {

    private val activity: Activity =
        Robolectric.buildActivity(Activity::class.java).setup().get()

    private fun topMarginOf(view: View): Int =
        (view.layoutParams as LinearLayout.LayoutParams).topMargin

    private fun row() = TextView(activity)

    @Test
    fun `a group is spaced from what precedes it`() {
        assertTrue(topMarginOf(activity.group(listOf(row()))) > 0)
    }

    @Test
    fun `a group and a card are spaced identically`() {
        assertEquals(
            topMarginOf(activity.card { }),
            topMarginOf(activity.group(listOf(row()))),
        )
    }

    @Test
    fun `a group still fills the width`() {
        val params = activity.group(listOf(row())).layoutParams as LinearLayout.LayoutParams

        assertEquals(MATCH, params.width)
        assertEquals(WRAP, params.height)
    }
}
