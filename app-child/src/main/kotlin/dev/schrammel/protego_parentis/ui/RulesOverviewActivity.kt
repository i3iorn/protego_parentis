package dev.schrammel.protego_parentis.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.rules.Feature
import dev.schrammel.protego_parentis.sync.PolicyReplication
import dev.schrammel.protego_parentis.sync.RosterStore

/**
 * The map of every rule the family can set, in one place.
 *
 * The drawer carries the same destinations as plain rows, but a map answers the
 * question a row cannot: what is *on*, what is *off*, and what does each control
 * actually affect. One screen, every control, a state chip per row and the
 * worked examples one tap away — the tutorial is linked from here rather than
 * from whichever screens remembered it (#225).
 *
 * Routing goes through [RulesEntry.launchControl], exactly as the drawer does:
 * with one managed child — every family today — a row opens that control
 * directly, and with several the reader picks the child first.
 */
class RulesOverviewActivity : ParentRoleActivity() {

    private lateinit var column: LinearLayout

    override fun onParentPhoneReady(savedInstanceState: Bundle?) {
        column = contentColumn()
        showColumn(column)
    }

    override fun onParentPhoneResumed() = render()

    private fun render() {
        column.removeAllViews()
        column.addView(screenTitle(getString(R.string.parent_row_rules)))
        column.addView(bodyText(getString(R.string.parent_row_rules_detail)))

        val flags = disabledFeaturesByChild()

        column.addView(sectionHeader(getString(R.string.home_group_child_time)))
        listOf(
            Control(HomeDestination.BONUS, R.string.rules_row_bonus, R.string.rules_row_bonus_detail, Feature.BONUS),
            Control(HomeDestination.GOAL, R.string.rules_row_goal, R.string.rules_row_goal_detail, Feature.GOALS),
            Control(HomeDestination.REQUEST_INBOX, R.string.rules_row_requests, R.string.rules_row_requests_detail, Feature.REQUESTS),
            Control(HomeDestination.AUTO_APPROVE, R.string.rules_row_auto, R.string.rules_row_auto_detail, Feature.AUTO_APPROVE),
        ).forEach { column.addView(controlRow(it, enabled = featureVisible(it.feature, flags))) }

        column.addView(sectionHeader(getString(R.string.home_group_child_limits)))
        listOf(
            Control(HomeDestination.CONTEXT_RULES, R.string.context_title, R.string.context_explainer, Feature.CONTEXT_RULES),
            Control(HomeDestination.CATEGORIES, R.string.rules_row_categories, R.string.rules_row_categories_detail, Feature.CATEGORIES),
            Control(HomeDestination.APP_LIMITS, R.string.rules_row_app_limits, R.string.rules_row_app_limits_detail, Feature.APP_LIMITS),
            Control(HomeDestination.APP_CONTROL, R.string.rules_row_apps, R.string.rules_row_apps_detail, Feature.APP_CONTROL),
            Control(HomeDestination.WEB_FILTER, R.string.rules_row_web, R.string.rules_row_web_detail, Feature.WEB_FILTER),
            Control(HomeDestination.PLACES, R.string.rules_row_places, R.string.rules_row_places_detail, Feature.PLACES),
        ).forEach { column.addView(controlRow(it, enabled = featureVisible(it.feature, flags))) }

        // The worked examples, offered here rather than only on the screens
        // that remembered to link them (#225).
        addSection(
            column,
            null,
            listOf(
                navigationRow(
                    getString(R.string.rules_tutorial_title),
                    getString(R.string.rules_tutorial_row_detail),
                ) { startActivity(RulesTutorialActivity.intent(this)) },
            ),
        )
    }

    private data class Control(
        val destination: HomeDestination,
        val title: Int,
        val detail: Int,
        val feature: Feature,
    )

    /**
     * A control row: title and what it affects, with a state chip at the end.
     * A switched-off control is still tappable — turning it on happens on the
     * very screen the row opens.
     */
    private fun controlRow(control: Control, enabled: Boolean): LinearLayout {
        val chip = statusChip(
            getString(if (enabled) R.string.rules_state_on else R.string.rules_state_off),
            ContextCompat.getColor(
                this,
                if (enabled) R.color.state_ok else R.color.state_warning,
            ),
        )
        return navigationRow(
            title = getString(control.title),
            subtitle = getString(control.detail),
            trailing = chip,
        ) {
            RulesEntry.launchControl(this, control.destination)
        }
    }

    /** The same read ParentHomeActivity does, once per render. */
    private fun disabledFeaturesByChild(): List<Set<String>?> {
        val replication = PolicyReplication(this)
        return RosterStore(this).children().map { child -> replication.read(child.id)?.disabledFeatures }
    }

    companion object {
        fun intent(context: Context) = Intent(context, RulesOverviewActivity::class.java)
    }
}
