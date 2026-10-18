package dev.schrammel.protego_parentis.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.sync.RosterStore

/**
 * Where "Rules" from a home screen actually goes (#43, rules-UX phase A).
 *
 * It opens the rules list ([ContextRulesActivity]) directly, not the controls
 * hub ([RulesActivity]) — "Rules" has to mean rules, and the hub screen used to
 * sit in between as a level with no meaning of its own ("Rules → Rules" before
 * a rule was ever shown). The hub is now reached from the list, under "More
 * controls".
 *
 * With one managed phone — every family today — this never shows a picker at
 * all: [ContextRulesActivity] opens directly, targeting that one child. Nothing
 * here so much as constructs [RulesetPickerActivity] unless the roster actually
 * holds a second one, which is what keeps a single-child family from ever
 * seeing a hint that a second could exist (the constraint recorded on #43).
 */
object RulesEntry {
    fun launch(context: Context) {
        val children = RosterStore(context).children()
        if (children.size <= 1) {
            context.startActivity(ContextRulesActivity.intent(context, children.singleOrNull()?.id))
        } else {
            context.startActivity(RulesetPickerActivity.intent(context))
        }
    }
}

/** Which managed phone's rules to edit — only ever reached when there is more than one. */
class RulesetPickerActivity : ParentRoleActivity() {

    private lateinit var column: LinearLayout

    override fun onParentPhoneReady(savedInstanceState: Bundle?) {
        column = contentColumn()
        showColumn(column)
        render()
    }

    override fun onParentPhoneResumed() = render()

    private fun render() {
        column.removeAllViews()
        column.addView(screenTitle(getString(R.string.rules_picker_title)))
        column.addView(captionText(getString(R.string.rules_picker_hint)))

        val children = RosterStore(this).children()
        column.addView(
            group(
                children.map { child ->
                    navigationRow(title = child.name, subtitle = "") {
                        startActivity(ContextRulesActivity.intent(this, child.id))
                    }
                },
            ),
        )
    }

    companion object {
        fun intent(context: Context) = Intent(context, RulesetPickerActivity::class.java)
    }
}
