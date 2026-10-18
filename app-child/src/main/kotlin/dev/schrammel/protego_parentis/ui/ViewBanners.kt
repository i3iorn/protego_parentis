package dev.schrammel.protego_parentis.ui

import android.app.Activity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import dev.schrammel.protego_parentis.R

/**
 * The cards a home screen shows above its summary when something needs saying:
 * a family that is not finished being set up, news since the history was last
 * read, and the read-only notice a locked rules screen carries.
 */

/**
 * The card that says a family is not finished being set up.
 *
 * Null when there is nothing to say, which is the usual case — a family that works
 * should not carry a permanent card explaining that it works.
 *
 * [onOpen] always leads to the devices screen, because in every state that
 * produces a card, that is the screen with the button on it.
 */
internal fun Activity.familyBanner(snapshot: FamilySnapshot, onOpen: () -> Unit): View? {
    val title: String
    val detail: String

    when (snapshot.state) {
        FamilyState.SOMEONE_WAITING -> {
            title = resources.getQuantityString(
                R.plurals.family_waiting_title,
                snapshot.waiting,
                snapshot.waiting,
            )
            detail = getString(R.string.family_waiting_detail)
        }

        FamilyState.NOT_YET_PAIRED -> {
            title = getString(R.string.family_unpaired_title)
            detail = getString(R.string.family_unpaired_detail)
        }

        FamilyState.ALONE, FamilyState.READY -> return null
    }

    return card {
        addView(
            TextView(context).apply {
                text = title
                styleAs(R.style.TextAppearance_Dementor_Subtitle)
            },
        )
        addView(captionText(detail))
        addView(
            primaryActionButton().apply {
                setText(R.string.family_open_devices)
                setOnClickListener { onOpen() }
            },
        )
    }
}

/**
 * Something happened since this phone last opened the shared history.
 *
 * Null the same way [familyBanner] is: most opens have nothing to report, and a
 * screen should not carry a permanent card saying so.
 */
internal fun Activity.activityBanner(unseenCount: Int, onOpen: () -> Unit): View? {
    if (unseenCount == 0) return null

    return card {
        addView(
            TextView(context).apply {
                text = resources.getQuantityString(
                    R.plurals.activity_banner_title,
                    unseenCount,
                    unseenCount
                )
                styleAs(R.style.TextAppearance_Dementor_Subtitle)
            },
        )
        addView(captionText(getString(R.string.activity_banner_detail)))
        addView(
            primaryActionButton().apply {
                setText(R.string.activity_banner_open)
                setOnClickListener { onOpen() }
            },
        )
    }
}

/**
 * What a [ParentGatedActivity.supportsReadOnly] screen shows in place of any
 * write affordance, before the PIN is entered — see the decision recorded on
 * #43. [onUnlock] is expected to call [ParentGatedActivity.requestUnlock].
 */
internal fun Activity.readOnlyBanner(onUnlock: () -> Unit): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dimen(R.dimen.space_hairline), 0, dimen(R.dimen.space_md))
        addView(captionText(getString(R.string.rules_read_only_hint)))
        addView(
            primaryActionButton().apply {
                setText(R.string.rules_unlock_to_edit)
                setOnClickListener { onUnlock() }
            },
        )
    }
