package dev.schrammel.dementor_watch.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.schrammel.dementor_watch.R
import dev.schrammel.dementor_watch.context.ContextProvider
import dev.schrammel.dementor_watch.model.Condition
import dev.schrammel.dementor_watch.model.ContextRule
import dev.schrammel.dementor_watch.model.Place
import dev.schrammel.dementor_watch.model.RuleEffect
import dev.schrammel.dementor_watch.model.RuleTarget
import dev.schrammel.dementor_watch.rules.ContextRuleStore
import dev.schrammel.dementor_watch.rules.LocalPolicyStore

/**
 * Rule-making, on its own screen.
 *
 * Split out because contextual rules are a different kind of thing from a daily
 * limit: a limit is one number, a rule is a sentence — *when this, do that to
 * those*. Mixing them into the settings list made both harder to read.
 *
 * The screen leads with which context sources actually work on this phone. A rule
 * that silently never fires because a permission was refused is the worst outcome
 * here, so the state is stated before any rule is written.
 */
class ContextRulesActivity : ParentGatedActivity() {

    private lateinit var store: ContextRuleStore
    private lateinit var policyStore: LocalPolicyStore
    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ContextRuleStore(this)
        policyStore = LocalPolicyStore(this)

        container = contentColumn()
        showColumn(container)
    }

    override fun onGatedContentVisible() {
        Background.load(this, {
            Triple(store.rules, store.places, ContextProvider(this).capabilities())
        }) { (rules, places, capabilities) ->
            render(rules, places, capabilities)
        }
    }

    private fun render(
        rules: List<ContextRule>,
        places: List<Place>,
        capabilities: dev.schrammel.dementor_watch.context.ContextCapabilities,
    ) {
        container.removeAllViews()
        container.addView(title(getString(R.string.context_title), R.style.TextAppearance_Dementor_Title))
        container.addView(caption(getString(R.string.context_explainer)))

        // What works right now, stated up front.
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

        container.addView(
            Button(this).apply {
                setText(R.string.context_manage_places)
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                    .apply { topMargin = dimen(R.dimen.space_md) }
                setOnClickListener { startActivity(PlacesActivity.intent(this@ContextRulesActivity)) }
            },
        )

        container.addView(title(getString(R.string.context_rules_header), R.style.TextAppearance_Dementor_Section))
        if (rules.isEmpty()) {
            container.addView(caption(getString(R.string.context_no_rules)))
        } else {
            rules.forEach { container.addView(ruleRow(it, places)) }
        }

        container.addView(
            Button(this).apply {
                setText(R.string.context_add_rule)
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                    .apply { topMargin = dimen(R.dimen.space_md) }
                setOnClickListener {
                    startActivity(ContextRuleEditActivity.intent(this@ContextRulesActivity))
                }
            },
        )
    }

    private fun ruleRow(rule: ContextRule, places: List<Place>): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dimen(R.dimen.space_sm), 0, dimen(R.dimen.space_sm))
            isClickable = true
            setOnClickListener {
                startActivity(
                    ContextRuleEditActivity.intent(this@ContextRulesActivity, rule.id),
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
    private fun describe(rule: ContextRule, places: List<Place>): String {
        val what = when (val effect = rule.effect) {
            is RuleEffect.Block -> getString(R.string.context_effect_block)
            is RuleEffect.Allow -> getString(R.string.context_effect_allow)
            is RuleEffect.SetDeviceBudget ->
                getString(R.string.context_effect_budget, formatDuration(effect.budget.default))
        }
        val who = when (val target = rule.target) {
            is RuleTarget.Everything -> getString(R.string.context_target_everything)
            is RuleTarget.Apps -> resources.getQuantityString(
                R.plurals.rules_category_apps,
                target.packages.size,
                target.packages.size,
            )

            is RuleTarget.Categories -> target.categories
                .mapNotNull { id -> policyStore.categories.firstOrNull { it.id == id.value }?.name }
                .joinToString(", ")
                .ifBlank { getString(R.string.context_target_categories) }
        }
        return getString(R.string.context_rule_summary, describeCondition(rule.condition, places), what, who)
    }

    private fun describeCondition(condition: Condition, places: List<Place>): String = when (condition) {
        is Condition.Always -> getString(R.string.context_when_always)
        is Condition.OnWifi -> getString(R.string.context_when_wifi, condition.ssid)
        is Condition.NearDevice -> getString(R.string.context_when_near, condition.tag.value)
        is Condition.AtPlace -> getString(
            R.string.context_when_place,
            places.firstOrNull { it.id == condition.place }?.name
                ?: getString(R.string.context_place_unknown),
        )

        is Condition.BatteryBelow -> getString(R.string.context_when_battery, condition.percent)

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
        fun intent(context: Context) = Intent(context, ContextRulesActivity::class.java)
    }
}
