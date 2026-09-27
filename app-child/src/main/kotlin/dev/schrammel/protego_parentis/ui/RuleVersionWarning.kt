package dev.schrammel.protego_parentis.ui

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.policy.ContextRuleFormat
import dev.schrammel.protego_parentis.rules.ContextRuleStore
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.RoleStore
import dev.schrammel.protego_parentis.sync.PolicyReplication
import dev.schrammel.protego_parentis.sync.RosterStore

/**
 * Whether this family mixes phones that read V2 rules with phones that may
 * not (see [ContextRuleFormat]).
 *
 * Shown on every phone that can show it, on the home screen and the rules
 * list, and kept there until every phone reports 1.2.0 or later: an older
 * child's phone deletes V2 rules on its next sync, and a warning seen only
 * at the moment of saving is too easy to miss.
 *
 * Reads preference files, so call it off the main thread.
 */
internal object RuleVersionWarning {

    fun read(context: Context): ContextRuleFormat.MixedVersions? {
        val roster = RosterStore(context)
        val phones = roster.devices.map { it.name to it.appVersion }
        if (phones.isEmpty()) return null
        return ContextRuleFormat.mixedVersions(phones, knownRules(context, roster))
    }

    /**
     * Every rule this phone can see: its own on the managed phone, each
     * child's replicated rules on a parent's.
     */
    private fun knownRules(context: Context, roster: RosterStore): List<ContextRule> =
        if (RoleStore(context).roleOrChild == DeviceRole.CHILD) {
            ContextRuleStore(context).rules
        } else {
            val replication = PolicyReplication(context)
            roster.children().flatMap { replication.readContextRules(it.id) }
        }
}

/**
 * The warning card for [warning], or null when every phone reads V2 rules.
 * Red once a V2 rule already exists, since then something is about to be lost.
 */
internal fun Activity.ruleVersionBanner(
    warning: ContextRuleFormat.MixedVersions?,
    onOpen: () -> Unit,
): View? {
    if (warning == null) return null
    val names = warning.olderPhones.joinToString(", ")
    val (titleRes, bodyRes, colorRes) = if (warning.rulesAtRisk) {
        Triple(R.string.rules_version_risk_title, R.string.rules_version_risk_body, R.color.state_critical)
    } else {
        Triple(R.string.rules_version_mixed_title, R.string.rules_version_mixed_body, R.color.state_warning)
    }
    return card {
        addView(
            TextView(context).apply {
                setText(titleRes)
                styleAs(R.style.TextAppearance_Dementor_Subtitle)
                setTextColor(ContextCompat.getColor(context, colorRes))
            },
        )
        addView(captionText(getString(bodyRes, names, warning.version)))
        addView(
            actionButton().apply {
                setText(R.string.family_open_devices)
                setOnClickListener { onOpen() }
            },
        )
    }
}
