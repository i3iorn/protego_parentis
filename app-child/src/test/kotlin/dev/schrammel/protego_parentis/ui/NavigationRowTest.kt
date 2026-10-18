package dev.schrammel.protego_parentis.ui

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * The chevron a [navigationRow] carries: present when the row opens a screen,
 * absent when it runs a local action, and never announced to a screen reader.
 */
@RunWith(RobolectricTestRunner::class)
class NavigationRowTest {

    private val activity: Activity =
        Robolectric.buildActivity(Activity::class.java).setup().get()

    private fun chevrons(row: View): List<TextView> =
        (0 until (row as ViewGroup).childCount)
            .map { row.getChildAt(it) }
            .filterIsInstance<TextView>()
            .filter { it.text == "›" }

    @Test
    fun `a navigation row carries a chevron`() {
        val row = activity.row(android.R.string.ok, android.R.string.cancel) {}

        assertEquals(1, chevrons(row).size)
    }

    @Test
    fun `an action row carries no chevron`() {
        val row = activity.row(android.R.string.ok, android.R.string.cancel, showChevron = false) {}

        assertTrue("action row should not promise a destination", chevrons(row).isEmpty())
    }

    @Test
    fun `the chevron is decorative to a screen reader`() {
        val row = activity.row(android.R.string.ok, android.R.string.cancel) {}
        val chevron = chevrons(row).single()

        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, chevron.importantForAccessibility)
        assertEquals(null, chevron.contentDescription)
    }
}
