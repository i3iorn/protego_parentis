package dev.schrammel.protego_parentis.ui

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import dev.schrammel.protego_parentis.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * The home screens' navigation drawer: the menu dialog's replacement.
 */
@RunWith(RobolectricTestRunner::class)
class HomeDrawerTest {

    private val activity: Activity =
        Robolectric.buildActivity(Activity::class.java).setup().get()

    private fun section(vararg rows: HomeRow) = HomeSection(
        label = null,
        rows = rows.toList(),
    )

    private val weekRow = HomeRow(
        R.string.home_open_week,
        R.string.home_open_week_detail,
        HomeDestination.WEEK,
    )

    private val rulesRow = HomeRow(
        R.string.parent_row_rules,
        R.string.parent_row_rules_detail,
        HomeDestination.RULES,
    )

    private fun openDrawer(vararg rows: HomeRow, onOpen: (HomeDestination) -> Unit = {}) {
        activity.showHomeDrawer(listOf(section(*rows)), onOpen)
    }

    @Test
    fun `choosing a row removes the drawer and opens the destination`() {
        var opened: HomeDestination? = null
        openDrawer(weekRow, onOpen = { opened = it })

        val title = requireNotNull(findText(decor(), activity.getString(R.string.home_open_week)))
        clickRow(title)

        assertEquals("the row must open its destination", HomeDestination.WEEK, opened)
        // The drawer must be gone before the screen opens — left standing, it
        // floats over the screen the reader just chose.
        assertNull("the drawer must be gone before the screen opens", findDrawer())
    }

    @Test
    fun `each row carries its title and its one-line description`() {
        openDrawer(weekRow)

        val texts = textsIn(decor())
        assertTrue(
            "the row must carry its title: $texts",
            texts.any { it == activity.getString(R.string.home_open_week) },
        )
        assertTrue(
            "the row must carry its description: $texts",
            texts.any { it == activity.getString(R.string.home_open_week_detail) },
        )
    }

    @Test
    fun `search filters the rows by their own words`() {
        openDrawer(weekRow, rulesRow)

        typeIntoSearch("week")

        val texts = textsIn(decor())
        assertTrue(
            "the matching row must remain: $texts",
            texts.any { it == activity.getString(R.string.home_open_week) },
        )
        assertTrue(
            "the non-matching row must be filtered out: $texts",
            texts.none { it == activity.getString(R.string.parent_row_rules) },
        )
    }

    @Test
    fun `search matches a row's description, not only its title`() {
        openDrawer(weekRow, rulesRow)

        // A word that appears only in the rules row's description.
        typeIntoSearch(firstWord(activity.getString(R.string.parent_row_rules_detail)))

        val texts = textsIn(decor())
        assertTrue(
            "the row whose description matches must remain: $texts",
            texts.any { it == activity.getString(R.string.parent_row_rules) },
        )
        assertTrue(
            "the row nothing matched must be filtered out: $texts",
            texts.none { it == activity.getString(R.string.home_open_week) },
        )
    }

    @Test
    fun `a search that matches nothing says so`() {
        openDrawer(weekRow)
        val before = textsIn(decor())
        assertTrue(before.any { it == activity.getString(R.string.home_open_week) })

        typeIntoSearch("qqzzqx")

        val texts = textsIn(decor())
        assertTrue(
            "the no-match caption must appear: $texts",
            texts.any { it == activity.getString(R.string.drawer_no_match) },
        )
        assertTrue(
            "the unmatched row must be gone: $texts",
            texts.none { it == activity.getString(R.string.home_open_week) },
        )
    }

    /** The word up to the first space, lowercased — typed as a person would. */
    private fun firstWord(text: String) = text.trim().split(" ").first().lowercase()

    private fun typeIntoSearch(query: String) {
        val field = requireNotNull(findView(decor()) { it is EditText }) {
            "the drawer must carry a search field"
        } as EditText
        field.setText(query)
    }

    private fun decor(): View = activity.window.decorView

    private fun findDrawer(): View? {
        val content = decor().findViewById<ViewGroup>(android.R.id.content)
        return content.getChildAt(content.childCount - 1)
            ?.takeIf { content.childCount > 1 }
    }

    /** Clicks the row carrying [title], rather than the text inside it. */
    private fun clickRow(title: TextView) {
        var row: View = title
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

    private fun findView(view: View, predicate: (View) -> Boolean): View? {
        if (predicate(view)) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findView(view.getChildAt(i), predicate)?.let { return it }
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
