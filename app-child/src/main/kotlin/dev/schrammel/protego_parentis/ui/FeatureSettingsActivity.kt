package dev.schrammel.protego_parentis.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.audit.Audit
import dev.schrammel.protego_parentis.model.AuditAction
import dev.schrammel.protego_parentis.model.AuditActor
import dev.schrammel.protego_parentis.rules.Feature
import dev.schrammel.protego_parentis.sync.RosterStore
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.ui.rules.RuleTarget
import dev.schrammel.protego_parentis.ui.rules.RuleTargets
import dev.schrammel.protego_parentis.ui.rules.policyRuleStore

/**
 * Which parts of the app [target]'s family uses (#91).
 *
 * PIN-gated, and recorded in the shared history. Putting a feature away changes
 * what the child can see and reach — the request screen, the emergency button's
 * neighbours, the browsing summary — so it is a rule change in everything but
 * name, and the child should be able to find out that it happened.
 *
 * A rule like any other now, replicated per managed child the same way a
 * daily limit or a schedule is, rather than a setting each phone kept for
 * itself — a parent editing it here changes what that specific child sees,
 * wherever the family looks.
 *
 * The three that cannot be put away are listed with their reasons rather than
 * omitted. A missing switch reads as an oversight; a switch that is not there and
 * says why reads as a decision, which is what it is.
 */
class FeatureSettingsActivity : ParentGatedActivity() {

    private lateinit var target: RuleTarget
    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        column = contentColumn()
        showColumn(column)
    }

    override fun onGatedContentVisible() {
        val resolved = RuleTargets.resolve(this) ?: return finish()
        target = resolved
        if (!requireStillInFamily(target)) return
        render()
    }

    override fun onGatedContentHidden() {
        column.removeAllViews()
    }

    private fun disabled(): Set<String> = policyRuleStore(this, target).read()?.disabledFeatures.orEmpty()

    private fun isEnabled(feature: Feature): Boolean = !feature.optional || feature.name !in disabled()

    private fun setEnabled(feature: Feature, enabled: Boolean) {
        if (!feature.optional) return
        val snapshot = policyRuleStore(this, target).read() ?: return
        val updated = if (enabled) snapshot.disabledFeatures - feature.name else snapshot.disabledFeatures + feature.name
        policyRuleStore(this, target).write(snapshot.copy(disabledFeatures = updated))
    }

    private fun render() {
        column.removeAllViews()
        column.addView(screenTitle(getString(R.string.features_title)))
        column.addView(bodyText(getString(R.string.features_intro)))
        if (!target.isOwnDevice) {
            val name = RosterStore(this).children().firstOrNull { it.id == target.childId }?.name
            if (name != null) column.addView(captionText(getString(R.string.features_editing_whose, name)))
        }
        // Putting a feature away does not undo it — the one thing about this
        // screen that could surprise somebody.
        column.addView(captionText(getString(R.string.features_still_apply)))

        val (optional, fixed) = Feature.entries.partition { it.optional }

        column.addView(sectionHeader(getString(R.string.features_choose_header)))
        column.addView(group(optional.map(::switchRow)))

        column.addView(sectionHeader(getString(R.string.features_always_header)))
        column.addView(captionText(getString(R.string.features_always_hint)))
        column.addView(group(fixed.map(::fixedRow)))
    }

    private fun switchRow(feature: Feature): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        setPadding(0, dimen(R.dimen.space_sm), 0, dimen(R.dimen.space_sm))

        addView(
            CheckBox(context).apply {
                setText(titleOf(feature))
                isChecked = isEnabled(feature)
                setOnCheckedChangeListener { _, wanted ->
                    setEnabled(feature, wanted)
                    Audit.record(
                        context = this@FeatureSettingsActivity,
                        actor = AuditActor.PARENT,
                        action = AuditAction.FEATURE_CHANGED,
                        subject = getString(titleOf(feature)),
                        after = Audit.flag(wanted),
                    )
                }
            },
        )
        addView(
            TextView(context).apply {
                setText(detailOf(feature))
                styleAs(R.style.TextAppearance_Dementor_Caption)
            },
        )
    }

    private fun fixedRow(feature: Feature): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        setPadding(0, dimen(R.dimen.space_sm), 0, dimen(R.dimen.space_sm))

        addView(
            TextView(context).apply {
                setText(titleOf(feature))
                styleAs(R.style.TextAppearance_Dementor_ListItem)
            },
        )
        addView(
            TextView(context).apply {
                setText(detailOf(feature))
                styleAs(R.style.TextAppearance_Dementor_Caption)
            },
        )
    }

    private fun titleOf(feature: Feature): Int = when (feature) {
        Feature.REQUESTS -> R.string.rules_row_requests
        Feature.AUTO_APPROVE -> R.string.rules_row_auto
        Feature.BONUS -> R.string.rules_row_bonus
        Feature.CATEGORIES -> R.string.rules_row_categories
        Feature.APP_LIMITS -> R.string.rules_row_app_limits
        Feature.SCHEDULES -> R.string.rules_row_schedules
        Feature.CONTEXT_RULES -> R.string.rules_row_context
        Feature.PLACES -> R.string.rules_row_places_alerts
        Feature.GOALS -> R.string.rules_row_goal
        Feature.APP_CONTROL -> R.string.rules_row_apps
        Feature.WEB_FILTER -> R.string.rules_row_web
        Feature.WEB_ACTIVITY -> R.string.rules_row_web_activity
        Feature.LOCATION -> R.string.parent_row_locate
        Feature.CHAT -> R.string.chat_title
        Feature.EMERGENCY -> R.string.sos_button
        Feature.HISTORY -> R.string.audit_title
        Feature.TRANSPARENCY -> R.string.privacy_title
    }

    private fun detailOf(feature: Feature): Int = when (feature) {
        Feature.REQUESTS -> R.string.features_requests_detail
        Feature.AUTO_APPROVE -> R.string.rules_row_auto_detail
        Feature.BONUS -> R.string.rules_row_bonus_detail
        Feature.CATEGORIES -> R.string.rules_row_categories_detail
        Feature.APP_LIMITS -> R.string.rules_row_app_limits_detail
        Feature.SCHEDULES -> R.string.rules_row_schedules_detail
        Feature.CONTEXT_RULES -> R.string.rules_row_context_detail
        Feature.PLACES -> R.string.rules_row_places_alerts_detail
        Feature.GOALS -> R.string.rules_row_goal_detail
        Feature.APP_CONTROL -> R.string.rules_row_apps_detail
        Feature.WEB_FILTER -> R.string.rules_row_web_detail
        Feature.WEB_ACTIVITY -> R.string.rules_row_web_activity_detail
        Feature.LOCATION -> R.string.features_location_detail
        Feature.CHAT -> R.string.features_chat_detail
        Feature.EMERGENCY -> R.string.features_emergency_reason
        Feature.HISTORY -> R.string.features_history_reason
        Feature.TRANSPARENCY -> R.string.features_transparency_reason
    }

    companion object {
        fun intent(context: Context, childId: DeviceId? = null) =
            RuleTargets.putExtra(Intent(context, FeatureSettingsActivity::class.java), childId)
    }
}
