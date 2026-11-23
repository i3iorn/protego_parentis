package dev.schrammel.protego_parentis.ui

import android.app.Activity
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.schrammel.protego_parentis.R

/**
 * The home screens' navigation drawer, replacing the modal menu dialog.
 *
 * The dialog was the primary navigation for both roles and had three costs: it
 * hid the whole map behind a tap, it vanished back into the dashboard on every
 * choice, and with fifteen rows in five groups it could only be scanned top to
 * bottom. The drawer keeps the same sections and rows — nothing about where a
 * row leads has changed — but it is a panel that slides in over a scrim, and it
 * carries a search field, because fifteen destinations are enough that finding
 * one by its own words beats reading every row.
 *
 * The panel is a plain view overlay on the activity's content frame rather than
 * a Dialog, so it is testable without shadow machinery and cannot float over
 * the screen a row opens — closing removes the view outright before
 * [HomeDestinationHandler.open] runs.
 */
internal fun Activity.showHomeDrawer(
    sections: List<HomeSection>,
    handler: HomeDestinationHandler,
) {
    val frame = findViewById<FrameLayout>(android.R.id.content)
    val panelWidth = (resources.displayMetrics.widthPixels * PANEL_FRACTION).toInt()
    var overlay: FrameLayout? = null

    /** Removes the drawer, then hands over. Gone first, always. */
    fun close(onClosed: () -> Unit = {}) {
        overlay?.let { frame.removeView(it) }
        overlay = null
        onClosed()
    }

    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    fun matches(row: HomeRow, query: String): Boolean {
        if (query.isBlank()) return true
        val needle = query.trim()
        return getString(row.title).contains(needle, ignoreCase = true) ||
            getString(row.detail).contains(needle, ignoreCase = true)
    }

    fun render(query: String) {
        list.removeAllViews()
        var shown = 0
        sections.forEach { section ->
            val rows = section.rows.filter { matches(it, query) }
            if (rows.isEmpty()) return@forEach
            shown += rows.size
            addSection(
                list,
                section.label?.let(::getString),
                rows.map { entry ->
                    navigationRow(
                        title = getString(entry.title),
                        subtitle = getString(entry.detail),
                        showChevron = !entry.destination.isAction,
                        icon = iconFor(entry.destination),
                    ) {
                        close { handler.open(entry.destination) }
                    }
                },
            )
        }
        // Said, not implied: a search that finds nothing must not look like a
        // drawer that failed to open.
        if (shown == 0) {
            list.addView(captionText(getString(R.string.drawer_no_match)))
        }
    }

    val search = EditText(this).apply {
        hint = getString(R.string.drawer_search_hint)
        addTextChangedListener(
            object : TextWatcher {
                override fun afterTextChanged(text: Editable?) = render(text?.toString().orEmpty())
                override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) = Unit
                override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) = Unit
            },
        )
    }

    val scroll = ScrollView(this).apply {
        addView(list, LinearLayout.LayoutParams(MATCH, WRAP))
        keepFieldsVisible()
    }

    val panel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        // The activity's own background colour, resolved from the theme, so
        // day and night both get the surface they already draw with.
        val typed = theme.obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground))
        setBackgroundColor(typed.getColor(0, 0xFF111111.toInt()))
        typed.recycle()
        val pad = dimen(R.dimen.screen_padding)
        setPadding(pad, dimen(R.dimen.space_sm), pad, pad)
        translationX = panelWidth.toFloat()
        addView(search, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(
            scroll,
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dimen(R.dimen.space_sm) },
        )
    }

    val scrim = View(this).apply {
        setBackgroundColor(SCRIM_COLOR)
        alpha = 0f
        setOnClickListener { close() }
    }

    val drawer = FrameLayout(this).apply {
        addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
        addView(
            panel,
            FrameLayout.LayoutParams(panelWidth, MATCH, Gravity.END),
        )
    }

    // Slides in once measured; the entrance is decoration and starts after the
    // first layout pass, so tests (and a slow device) always see the final view.
    frame.addView(drawer, FrameLayout.LayoutParams(MATCH, MATCH))
    overlay = drawer
    panel.post {
        panel.animate().translationX(0f).setDuration(ENTRY_MILLIS).start()
    }
    scrim.animate().alpha(1f).setDuration(ENTRY_MILLIS).start()

    render("")
}

private const val PANEL_FRACTION = 0.82f
private const val ENTRY_MILLIS = 180L
private const val SCRIM_COLOR = 0x99000000.toInt()
