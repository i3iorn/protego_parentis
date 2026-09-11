package dev.schrammel.protego_parentis.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.format.formatDuration
import dev.schrammel.protego_parentis.context.ContextCapabilities
import dev.schrammel.protego_parentis.context.ContextProvider
import dev.schrammel.protego_parentis.model.CategoryId
import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.PlaceRef
import dev.schrammel.protego_parentis.model.RuleEffect
import dev.schrammel.protego_parentis.model.RuleTarget
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.ui.rules.categories
import dev.schrammel.protego_parentis.ui.rules.contextRuleRuleStore
import dev.schrammel.protego_parentis.ui.rules.placeRuleStore
import dev.schrammel.protego_parentis.ui.rules.policyRuleStore
import dev.schrammel.protego_parentis.ui.rules.RuleTargets

/**
 * Rule-making, on its own screen, on whichever kind of phone edits [target]'s
 * rules (#43).
 *
 * Split out because contextual rules are a different kind of thing from a daily
 * limit: a limit is one number, a rule is a sentence — *when this, do that to
 * those*. Mixing them into the settings list made both harder to read.
 *
 * The screen leads with which context sources actually work on this phone —
 * only on the managed phone itself, though: [ContextProvider.capabilities]
 * describes whichever device is running the code, which on a parent's phone
 * would answer the wrong question entirely. Editing a remote child's rules
 * skips that section rather than showing a capability reading for the wrong
 * device.
 */
class ContextRulesActivity : ParentGatedActivity() {

    private lateinit var target: dev.schrammel.protego_parentis.ui.rules.RuleTarget
    private lateinit var container: LinearLayout

    override fun supportsReadOnly() = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = contentColumn()
        showColumn(container)
    }

    override fun onGatedContentVisible() {
        val resolved = RuleTargets.resolve(this) ?: return finish()
        target = resolved
        if (!requireStillInFamily(target)) return

        Background.load(this, {
            val rules = contextRuleRuleStore(this, target).read()
            val places = placeRuleStore(this, target).read()
            val capabilities = if (target.isOwnDevice) ContextProvider(this).capabilities() else null
            Triple(rules, places, capabilities)
        }) { (rules, places, capabilities) ->
            render(rules, places, capabilities)
        }
    }

    private fun render(
        rules: List<ContextRule>,
        places: List<PlaceRef>,
        capabilities: ContextCapabilities?,
    ) {
        container.removeAllViews()
        container.addView(title(getString(R.string.context_title), R.style.TextAppearance_Dementor_Title))
        container.addView(caption(getString(R.string.context_explainer)))
        if (!canEdit) container.addView(readOnlyBanner { requestUnlock { onGatedContentVisible() } })

        // What works right now, stated up front — only meaningful for this
        // device's own sensors.
        if (capabilities != null) {
            container.addView(title(getString(R.string.context_sources_header), R.style.TextAppearance_Dementor_Section))
            container.addView(
                caption(
                    getString(
                        if (capabilities.canReadWifi) R.string.context_wifi_ready else R.string.context_wifi_blocked,
                    ),
                ),
            )
            container.addView(
                caption(
                    getString(
                        if (capabilities.canReadLocation) R.string.context_place_ready else R.string.context_place_blocked,
                    ),
                ),
            )
            container.addView(
                caption(
                    getString(
                        if (capabilities.canReadNearbyDevices) {
                            R.string.context_near_ready
                        } else {
                            R.string.context_near_blocked
                        },
                    ),
                ),
            )
        }

        container.addView(
            Button(this).apply {
                setText(R.string.context_manage_places)
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                    .apply { topMargin = dimen(R.dimen.space_md) }
                setOnClickListener {
                    startActivity(PlacesActivity.intent(this@ContextRulesActivity, target.childId))
                }
            },
        )

        container.addView(title(getString(R.string.context_rules_header), R.style.TextAppearance_Dementor_Section))
        if (rules.isEmpty()) {
            container.addView(caption(getString(R.string.context_no_rules)))
        } else {
            rules.forEach { container.addView(ruleRow(it, places)) }
        }

        if (canEdit) {
            container.addView(
                Button(this).apply {
                    setText(R.string.context_add_rule)
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                        .apply { topMargin = dimen(R.dimen.space_md) }
                    setOnClickListener {
                        startActivity(ContextRuleEditActivity.intent(this@ContextRulesActivity, childId = target.childId))
                    }
                },
            )
        }
    }

    private fun ruleRow(rule: ContextRule, places: List<PlaceRef>): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dimen(R.dimen.space_sm), 0, dimen(R.dimen.space_sm))
            isClickable = true
            setOnClickListener {
                startActivity(
                    ContextRuleEditActivity.intent(this@ContextRulesActivity, rule.id, target.childId),
                )
            }

            addView(
                TextView(context).apply {
                    text = if (rule.enabled) rule.name else getString(R.string.context_rule_off, rule.name)
                    styleAs(R.style.TextAppearance_Dementor_ListItem)
                },
            )
            addView(
                TextView(context).apply {
                    text = describe(rule, places)
                    styleAs(R.style.TextAppearance_Dementor_Caption)
                },
            )
        }

    /** The rule as a sentence, which is how a parent thinks about it. */
    private fun describe(rule: ContextRule, places: List<PlaceRef>): String {
        val what = when (val effect = rule.effect) {
            is RuleEffect.Block -> getString(R.string.context_effect_block)
            is RuleEffect.Allow -> getString(R.string.context_effect_allow)
            is RuleEffect.SafetyExempt -> getString(R.string.context_effect_safety_exempt)
            is RuleEffect.SetDeviceBudget ->
                getString(R.string.context_effect_budget, formatDuration(effect.budget.default))
        }
        val who = when (val ruleTarget = rule.target) {
            is RuleTarget.Everything -> getString(R.string.context_target_everything)
            is RuleTarget.Apps -> describeApps(ruleTarget.packages)
            is RuleTarget.Categories -> describeCategories(ruleTarget.categories)
            is RuleTarget.Mixed ->
                getString(R.string.context_target_mixed, describeApps(ruleTarget.packages), describeCategories(ruleTarget.categories))
            is RuleTarget.EverythingExcept ->
                getString(R.string.context_target_everything_except, describeApps(ruleTarget.packages))
        }
        return getString(R.string.context_rule_summary, describeCondition(rule.condition, places), what, who)
    }

    private fun describeApps(packages: Set<PackageName>): String =
        resources.getQuantityString(R.plurals.rules_category_apps, packages.size, packages.size)

    private fun describeCategories(categories: Set<CategoryId>): String = categories
        .mapNotNull { id -> policyRuleStore(this, target).categories().firstOrNull { it.id == id.value }?.name }
        .joinToString(", ")
        .ifBlank { getString(R.string.context_target_categories) }

    private fun describeCondition(condition: Condition, places: List<PlaceRef>): String = when (condition) {
        is Condition.Always -> getString(R.string.context_when_always)
        is Condition.OnWifi -> getString(R.string.context_when_wifi, condition.ssid)
        is Condition.NearDevice -> getString(R.string.context_when_near, condition.tag.value)
        is Condition.AtPlace -> getString(
            R.string.context_when_place,
            places.firstOrNull { it.id == condition.place }?.name
                ?: getString(R.string.context_place_unknown),
        )

        is Condition.BatteryBelow -> getString(R.string.context_when_battery, condition.percent)

        // #101. Phrased from the same day and time vocabulary a schedule uses,
        // because to a parent this *is* a schedule — one written as a rule.
        // A rule carrying this can arrive by replication from a phone that
        // already speaks #72's language before this screen offers to create
        // one, and a condition it could not describe would read as a rule that
        // applies for no stated reason.
        is Condition.DuringWindow -> getString(
            if (condition.window.parityAnchor == null) {
                R.string.context_when_window
            } else {
                R.string.context_when_window_alternating
            },
            DayPicker.describe(this, condition.window.days),
            getString(
                R.string.schedules_window,
                formatTime(condition.window.start),
                formatTime(condition.window.end),
            ),
        )

        // #102. Same wording as the dedicated device budget's own screens use
        // for "minutes used", since to a parent this is that same daily limit,
        // just expressed as a rule.
        is Condition.AllowanceSpent -> getString(R.string.context_when_allowance_spent, condition.minutes)

        is Condition.Not -> getString(
            R.string.context_when_not,
            describeCondition(condition.condition, places),
        )

        is Condition.AllOf -> condition.conditions
            .joinToString(getString(R.string.context_join_and)) { describeCondition(it, places) }
            .ifBlank { getString(R.string.context_when_always) }

        is Condition.AnyOf -> condition.conditions
            .joinToString(getString(R.string.context_join_or)) { describeCondition(it, places) }
            .ifBlank { getString(R.string.context_when_never) }
    }

    private fun title(text: String, appearance: Int) = TextView(this).apply {
        this.text = text
        styleAs(appearance)
        setPadding(0, dimen(R.dimen.section_spacing), 0, dimen(R.dimen.space_xxs))
    }

    private fun caption(text: String) = TextView(this).apply {
        this.text = text
        styleAs(R.style.TextAppearance_Dementor_Caption)
    }

    companion object {
        fun intent(context: Context, childId: DeviceId? = null) =
            RuleTargets.putExtra(Intent(context, ContextRulesActivity::class.java), childId)
    }
}
