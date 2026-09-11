package dev.schrammel.protego_parentis.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.sync.RosterStore

/**
 * Where "which parts of the app" from a parent's phone actually goes (#91).
 *
 * The same shape as [RulesEntry] and for the same reason: with one managed
 * phone — every family today — this never shows a picker at all,
 * [FeatureSettingsActivity] opens directly, targeting that one child.
 */
object FeatureSettingsEntry {
    fun launch(context: Context) {
        val children = RosterStore(context).children()
        if (children.size <= 1) {
            context.startActivity(FeatureSettingsActivity.intent(context, children.singleOrNull()?.id))
        } else {
            context.startActivity(FeatureSettingsPickerActivity.intent(context))
        }
    }
}

/** Which managed phone's parts to edit — only ever reached when there is more than one. */
class FeatureSettingsPickerActivity : ParentRoleActivity() {

    private lateinit var column: LinearLayout

    override fun onParentPhoneReady(savedInstanceState: Bundle?) {
        column = contentColumn()
        showColumn(column)
        render()
    }

    override fun onParentPhoneResumed() = render()

    private fun render() {
        column.removeAllViews()
        column.addView(screenTitle(getString(R.string.features_picker_title)))
        column.addView(captionText(getString(R.string.features_picker_hint)))

        val children = RosterStore(this).children()
        column.addView(
            group(
                children.map { child ->
                    navigationRow(title = child.name, subtitle = "") {
                        startActivity(FeatureSettingsActivity.intent(this, child.id))
                    }
                },
            ),
        )
    }

    companion object {
        fun intent(context: Context) = Intent(context, FeatureSettingsPickerActivity::class.java)
    }
}
