package dev.schrammel.protego_parentis.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.sync.RosterStore
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.ui.rules.RuleTargets

/**
 * Where "Rules" from a home screen actually goes (#43, rules-UX phase A).
 *
 * It opens the rules list ([ContextRulesActivity]) directly — "Rules" has
 * to mean rules, and the controls hub that used to sit in between was a level
 * with no meaning of its own ("Rules → Rules" before a rule was ever shown).
 * The controls that used to live behind that hub are in the home screen's menu
 * now, and [launchControl] opens them.
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

    /**
     * Opens one of the controls — categories, app limits, the web filter — for
     * the phone this screen is about.
     *
     * A parent's phone holds no rules of its own, so every one of these is
     * really about a child's phone: with one child it opens for that child
     * directly, and with several the reader picks which one first. A child's
     * own phone has no children in the roster at all, so every one of them
     * opens for this phone.
     */
    internal fun launchControl(context: Context, destination: HomeDestination) {
        val children = RosterStore(context).children()
        if (children.size <= 1) {
            context.startActivity(controlIntent(context, destination, children.singleOrNull()?.id))
        } else {
            context.startActivity(RulesetPickerActivity.intent(context, destination))
        }
    }

    /** The control screen for [destination] on [childId]'s phone. */
    internal fun controlIntentFor(
        context: Context,
        destination: HomeDestination,
        childId: DeviceId,
    ): Intent = controlIntent(context, destination, childId)

    /**
     * The control screen for [destination], for [childId] - or for this phone
     * when [childId] is null, which is what a child's own phone means.
     */
    private fun controlIntent(
        context: Context,
        destination: HomeDestination,
        childId: DeviceId?,
    ): Intent = when (destination) {
        // The rules list itself, named as a control because the rules map
        // ([RulesOverviewActivity]) draws it as one row among the others: it
        // leads to the very list every other row here edits a slice of.
        HomeDestination.CONTEXT_RULES -> ContextRulesActivity.intent(context, childId)
        HomeDestination.CATEGORIES ->
            RuleTargets.putExtra(Intent(context, CategoriesActivity::class.java), childId)
        HomeDestination.APP_LIMITS -> AppLimitsActivity.intent(context, childId)
        HomeDestination.APP_CONTROL -> AppControlActivity.intent(context, childId)
        HomeDestination.WEB_FILTER -> WebFilterActivity.intent(context)
        HomeDestination.PLACES -> PlacesActivity.intent(context, childId)
        HomeDestination.ARRIVALS -> PlaceAlertsActivity.intent(context, childId)
        HomeDestination.NIGHTSCOUT -> NightscoutActivity.intent(context)
        HomeDestination.GOAL -> Intent(context, GoalActivity::class.java)
        // Bonus time is one screen for a child's phone and another for a
        // parent's view of it, and which one this is follows from the same
        // question the rest of these do: whose phone is this about.
        HomeDestination.BONUS ->
            if (childId == null) BonusTimeActivity.intent(context) else ParentBonusActivity.intent(context)
        HomeDestination.REQUEST_INBOX -> RequestInboxActivity.intent(context)
        HomeDestination.AUTO_APPROVE -> AutoApprovalActivity.intent(context, childId)
        else -> error("$destination is not a control")
    }
}

/** Which managed phone's rules to edit — only ever reached when there is more than one. */
class RulesetPickerActivity : ParentRoleActivity() {

    private lateinit var column: LinearLayout

    /**
     * The control the picker is choosing a phone for, when it was opened to
     * reach one rather than the rules list.
     */
    private var destination: HomeDestination? = null

    override fun onParentPhoneReady(savedInstanceState: Bundle?) {
        destination = intent.getStringExtra(EXTRA_DESTINATION)?.let { name ->
            HomeDestination.entries.firstOrNull { it.name == name }
        }
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
                        val chosen = destination
                        if (chosen == null) {
                            startActivity(ContextRulesActivity.intent(this, child.id))
                        } else {
                            startActivity(RulesEntry.controlIntentFor(this, chosen, child.id))
                        }
                    }
                },
            ),
        )
    }

    companion object {
        private const val EXTRA_DESTINATION = "rulesPickerDestination"

        internal fun intent(context: Context, destination: HomeDestination? = null): Intent =            Intent(context, RulesetPickerActivity::class.java)
                .apply { destination?.let { putExtra(EXTRA_DESTINATION, it.name) } }
    }
}
