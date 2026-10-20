package dev.schrammel.protego_parentis.ui

import android.app.Activity
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import dev.schrammel.protego_parentis.R

/**
 * The home screen's menu: the destinations that used to be laid out inline.
 *
 * A home screen that answers one question and offers the rest on demand is a
 * dashboard; a home screen that answers all of them is a list of everything the
 * app can do, which is what the dashboard had become. The rows themselves did
 * not change - same titles, same one-line descriptions, same grouping - they
 * simply moved somewhere a menu belongs.
 *
 * The button carries a count of what is waiting, because a menu hides what is
 * in it and a badge is what says it is not empty.
 */

/**
 * The menu button in a home screen's header.
 *
 * A glyph rather than an icon asset: the app builds its screens in code and
 * deliberately carries no icon library, the same choice the "ⓘ" beside a
 * heading made. The count rides on the glyph, and the whole control speaks for
 * itself as "Menu" - the glyph is decorative, so a screen reader says what
 * the button does and not what it is drawn with.
 */
internal fun Activity.menuButton(waiting: Int, onClick: () -> Unit): TextView = TextView(this).apply {
    text = if (waiting > 0) "☰ $waiting" else "☰"
    styleAs(R.style.TextAppearance_Dementor_Headline)
    gravity = Gravity.CENTER
    minimumWidth = dimen(R.dimen.touch_target)
    minimumHeight = dimen(R.dimen.touch_target)
    contentDescription = if (waiting > 0) {
        resources.getQuantityString(R.plurals.home_menu_waiting, waiting, waiting)
    } else {
        getString(R.string.home_menu)
    }
    withRipple()
    setOnClickListener { onClick() }
}

/**
 * The menu itself: the sectioned rows, in a dialog that opens from the header.
 *
 * A dialog rather than a screen of its own, because a menu is a list of
 * places and a place you go to is not a place you stay: the reader picks a row
 * and the screen behind it is still the dashboard they came from.
 */
internal fun Activity.homeMenuDialog(
    sections: List<HomeSection>,
    onOpen: (HomeDestination) -> Unit,
) {
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dimen(R.dimen.screen_padding)
        setPadding(pad, dimen(R.dimen.space_sm), pad, pad)
    }

    // The dialog has to be gone before the screen behind it opens. Left
    // standing, it floats over the screen the reader just chose - and back
    // from there dismisses the menu again instead of returning to the
    // dashboard.
    lateinit var dialog: AlertDialog

    sections.forEach { section ->
        addSection(
            content,
            section.label?.let(::getString),
            section.rows.map { entry ->
                navigationRow(
                    title = getString(entry.title),
                    subtitle = getString(entry.detail),
                    // A chevron promises somewhere else to go, and the two
                    // actions in the menu - lock the phone now, find where it
                    // is - do not go anywhere.
                    showChevron = !entry.destination.isAction,
                ) {
                    dialog.dismiss()
                    onOpen(entry.destination)
                }
            },
        )
    }

    dialog = AlertDialog.Builder(this)
        .setTitle(R.string.home_menu)
        .setView(
            ScrollView(this).apply {
                addView(content, LinearLayout.LayoutParams(MATCH, WRAP))
            },
        )
        .setPositiveButton(android.R.string.ok, null)
        .create()
    dialog.show()
}
