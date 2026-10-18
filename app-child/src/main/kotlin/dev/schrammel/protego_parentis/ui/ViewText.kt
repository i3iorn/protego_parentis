package dev.schrammel.protego_parentis.ui

import android.app.Activity
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.ui.rules.RuleWriteKind

/**
 * The text pieces every screen is built from: titles, section headings, body and
 * caption lines, the little "ⓘ" that explains a heading, and the short messages
 * that stand in for a list that is legitimately empty.
 *
 * Split out of `Views.kt` so the scaffolding there stays about layout.
 */

/** The screen's name. One per screen, at the top. */
internal fun Activity.screenTitle(text: String): TextView = TextView(this).apply {
    this.text = text
    // Screens are built in code, so nothing else tells TalkBack where one
    // part of a screen ends; headings are what its heading navigation jumps
    // between.
    isAccessibilityHeading = true
    styleAs(R.style.TextAppearance_Dementor_Title)
    setPadding(0, dimen(R.dimen.space_sm), 0, dimen(R.dimen.space_lg))
}

/**
 * A group heading.
 *
 * Set in small spaced capitals in the quieter colour, so it reads as a label for
 * what follows rather than as another line of text competing with the title.
 */
internal fun Activity.sectionHeader(text: String): TextView = TextView(this).apply {
    this.text = text
    isAccessibilityHeading = true
    styleAs(R.style.TextAppearance_Dementor_Section)
    setPadding(0, dimen(R.dimen.section_spacing), 0, dimen(R.dimen.space_sm))
}

internal fun Activity.bodyText(text: String): TextView = TextView(this).apply {
    this.text = text
    styleAs(R.style.TextAppearance_Dementor_Body)
    setPadding(0, dimen(R.dimen.space_hairline), 0, dimen(R.dimen.space_hairline))
}

internal fun Activity.captionText(text: String): TextView = TextView(this).apply {
    this.text = text
    styleAs(R.style.TextAppearance_Dementor_Caption)
    setPadding(0, dimen(R.dimen.space_hairline), 0, dimen(R.dimen.space_xs))
}

/** Role, PIN, join — first-time setup's fixed sequence, for [stepIndicator]. */
internal const val ONBOARDING_STEPS = 3

/**
 * "Step 2 of 3," for the handful of screens that only ever run back-to-back
 * during first-time setup (role, PIN, join). Each of those screens otherwise
 * looks like a complete, self-contained thing, and backing out of the middle
 * one gives no sense of whether that abandons one step or the whole setup.
 */
internal fun Activity.stepIndicator(current: Int, total: Int = ONBOARDING_STEPS): TextView =
    captionText(getString(R.string.onboarding_step, current, total))

/**
 * The small "ⓘ" a heading carries when what it groups is not obvious from its
 * label alone — plain text rather than a drawable, the same choice
 * [navigationRow]'s chevron made, so it takes its colour from the theme with
 * no second asset to keep in sync with it.
 */
internal fun Activity.infoButton(@StringRes title: Int, @StringRes message: Int): TextView =
    TextView(this).apply {
        text = "ⓘ"
        styleAs(R.style.TextAppearance_Dementor_ListItem)
        setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        val pad = dimen(R.dimen.space_sm)
        setPadding(pad, 0, 0, 0)
        contentDescription = getString(title)
        withRipple()
        setOnClickListener { infoDialog(title, message) }
    }

/**
 * [heading] with an [infoButton] beside it. Takes the heading view itself
 * rather than building one, since [sectionHeader] and [ContextRuleEditActivity]'s
 * own differently-styled local heading both need this same pairing.
 */
internal fun Activity.headingWithInfo(
    heading: TextView,
    @StringRes infoTitle: Int,
    @StringRes infoMessage: Int,
): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    addView(heading.apply { layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f) })
    addView(infoButton(infoTitle, infoMessage))
}

/**
 * A consistent "nothing here" block: an optional short title and the line that
 * explains the absence. Used wherever a list can legitimately be empty, so
 * "there are none" reads the same everywhere rather than as a bare sentence.
 */
internal fun Activity.emptyState(
    @StringRes title: Int? = null,
    @StringRes hint: Int
): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dimen(R.dimen.space_sm), 0, dimen(R.dimen.space_sm))
        title?.let {
            addView(
                TextView(context).apply {
                    setText(it)
                    styleAs(R.style.TextAppearance_Dementor_Subtitle)
                },
            )
        }
        addView(captionText(getString(hint)))
    }

/**
 * "Nothing matched", which is not the same as an empty list.
 *
 * A screen with no apps at all and a screen whose filter excluded them need
 * different things from the reader, so they never share a line.
 */
internal fun Activity.noSearchResults(): LinearLayout = emptyState(hint = R.string.app_search_none)

/** A label and a value on one line, for a summary. */
internal fun Activity.statRow(label: String, value: String): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        setPadding(0, dimen(R.dimen.space_xs), 0, dimen(R.dimen.space_xs))

        addView(
            TextView(context).apply {
                text = label
                styleAs(R.style.TextAppearance_Dementor_Body)
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            },
        )
        addView(
            TextView(context).apply {
                text = value
                styleAs(R.style.TextAppearance_Dementor_Body)
            },
        )
    }

/**
 * A short message, gone on its own. The common case for "that worked" or "that
 * didn't". [duration] defaults to the ordinary case; pass `Toast.LENGTH_LONG`
 * for a message worth more time to read, such as an error explaining itself.
 */
internal fun Activity.toast(message: String, duration: Int = Toast.LENGTH_SHORT) =
    Toast.makeText(this, message, duration).show()

internal fun Activity.toast(@StringRes messageRes: Int, duration: Int = Toast.LENGTH_SHORT) =
    toast(getString(messageRes), duration)

/**
 * The acknowledgement a rule editor gives after writing through a
 * [dev.schrammel.protego_parentis.ui.rules.RuleWriteKind] store: a local write
 * is already live and needs no word, a remote one only reaches the child once
 * sync carries it there. Five screens used to hand-roll this branch; the store
 * knows which kind it is, so they ask it instead. A delete passes its own
 * [remoteMessage].
 */
internal fun Activity.acknowledgeRuleWrite(
    store: RuleWriteKind,
    @StringRes remoteMessage: Int = R.string.context_save_sent_remote,
) {
    if (store.writesRemotely) toast(remoteMessage)
}
