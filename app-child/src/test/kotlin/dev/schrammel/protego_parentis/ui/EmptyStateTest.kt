package dev.schrammel.protego_parentis.ui

import android.app.Activity
import android.view.ViewGroup
import android.widget.TextView
import dev.schrammel.protego_parentis.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * The shared "nothing here" block, so an empty list reads the same everywhere.
 */
@RunWith(RobolectricTestRunner::class)
class EmptyStateTest {

    private val activity: Activity =
        Robolectric.buildActivity(Activity::class.java).setup().get()

    private fun lines(state: ViewGroup): List<String> =
        (0 until state.childCount)
            .map { state.getChildAt(it) }
            .filterIsInstance<TextView>()
            .map { it.text.toString() }

    @Test
    fun `a title is shown above the hint when supplied`() {
        val state = activity.emptyState(
            title = R.string.parent_today_none,
            hint = R.string.parent_today_none_hint,
        )

        assertEquals(
            listOf(
                activity.getString(R.string.parent_today_none),
                activity.getString(R.string.parent_today_none_hint),
            ),
            lines(state),
        )
    }

    @Test
    fun `without a title, only the hint is shown`() {
        val state = activity.emptyState(hint = R.string.parent_today_none_hint)

        assertEquals(listOf(activity.getString(R.string.parent_today_none_hint)), lines(state))
    }

    @Test
    fun `no search results is an empty state`() {
        val state = activity.noSearchResults()

        assertTrue(lines(state).contains(activity.getString(R.string.app_search_none)))
    }
}
