package dev.schrammel.protego_parentis.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.audit.Audit
import dev.schrammel.protego_parentis.model.AuditAction
import dev.schrammel.protego_parentis.model.AuditActor
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.ui.rules.appCatalog
import dev.schrammel.protego_parentis.ui.rules.policyRuleStore
import dev.schrammel.protego_parentis.usage.AppInfo
import dev.schrammel.protego_parentis.ui.rules.RuleTarget
import dev.schrammel.protego_parentis.ui.rules.RuleTargets

/**
 * A daily limit for one individual app, on whichever kind of phone edits
 * [target]'s rules (#43).
 *
 * Separate from categories on purpose: a category answers "an hour of games in
 * total", this answers "and no more than twenty minutes of that one". Both apply,
 * and whichever runs out first is what blocks.
 */
class AppLimitsActivity : ParentGatedActivity() {

    private lateinit var target: RuleTarget
    private lateinit var container: LinearLayout
    private lateinit var rows: LinearLayout
    private var apps: List<AppInfo> = emptyList()
    private var limits: Map<String, Int> = emptyMap()
    private var query = ""

    override fun supportsReadOnly() = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = contentColumn()
        container.addView(
            TextView(this).apply {
                setText(R.string.app_limits_title)
                styleAs(R.style.TextAppearance_Dementor_Title)
            },
        )
        container.addView(
            TextView(this).apply {
                setText(R.string.app_limits_hint)
                styleAs(R.style.TextAppearance_Dementor_Caption)
                setPadding(0, dimen(R.dimen.space_xxs), 0, dimen(R.dimen.space_md))
            },
        )
        // Built once and never rebuilt. Recreating the field on each keystroke
        // would take the cursor and the typed text with it.
        container.addView(searchField { query = it; renderRows() })
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(rows)
        showColumn(container)
    }

    override fun onGatedContentVisible() {
        val resolved = RuleTargets.resolve(this) ?: return finish()
        target = resolved
        if (!requireStillInFamily(target)) return

        Background.load(this, {
            // Both the app list and the stored limits come off disk (or, for a
            // parent editing another device's rules, off the replica).
            appCatalog(this, target).launchable() to
                (policyRuleStore(this, target).read()?.appLimits ?: emptyMap())
        }) { (loadedApps, loadedLimits) ->
            apps = loadedApps
            limits = loadedLimits
            renderRows()
        }
    }

    private fun renderRows() {
        rows.removeAllViews()
        if (!canEdit) {
            rows.addView(readOnlyBanner { requestUnlock { onGatedContentVisible() } })
        }

        // Apps that already have a limit float to the top, so the ones the family
        // actually cares about are not buried in an alphabetical list of hundreds.
        // Ordering first and filtering second keeps that true while searching.
        val (limited, unlimited) = apps.partition { limits.containsKey(it.pkg.value) }
        val visible = AppSearch.filter(limited + unlimited, query, { it.label }, { it.pkg.value })
        if (visible.isEmpty() && apps.isNotEmpty()) {
            rows.addView(noSearchResults())
            return
        }
        visible.forEach { app -> rows.addView(row(app, limits[app.pkg.value] ?: 0)) }
    }

    private fun row(app: AppInfo, limitMinutes: Int): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dimen(R.dimen.space_sm), 0, dimen(R.dimen.space_sm))
            isClickable = canEdit

            addView(
                ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dimen(R.dimen.row_icon), dimen(R.dimen.row_icon))
                        .apply { marginEnd = dimen(R.dimen.space_md) }
                    setImageDrawable(app.icon)
                },
            )
            addView(
                TextView(context).apply {
                    text = app.label
                    styleAs(R.style.TextAppearance_Dementor_ListItem)
                    layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                },
            )
            addView(
                TextView(context).apply {
                    text = if (limitMinutes > 0) formatLimit(limitMinutes) else getString(R.string.app_limits_none)
                    styleAs(R.style.TextAppearance_Dementor_Body)
                },
            )

            if (canEdit) {
                setOnClickListener { promptForLimit(app, limitMinutes) }
            }
        }

    private fun promptForLimit(app: AppInfo, current: Int) {
        sliderDialog(
            title = app.label,
            max = LimitSlider.MAX_STEPS,
            initialProgress = LimitSlider.progressFor(current),
            format = { formatLimit(LimitSlider.minutesFor(it)) },
            positiveText = R.string.app_limits_save,
        ) { progress ->
            val minutes = LimitSlider.minutesFor(progress)
            val store = policyRuleStore(this, target)
            val snapshot = store.read() ?: return@sliderDialog
            val updatedLimits = snapshot.appLimits.toMutableMap().apply {
                if (minutes > 0) put(app.pkg.value, minutes) else remove(app.pkg.value)
            }
            store.write(snapshot.copy(appLimits = updatedLimits))
            Audit.record(
                context = this,
                actor = AuditActor.PARENT,
                action = AuditAction.APP_LIMIT_CHANGED,
                subject = app.label,
                before = Audit.minutes(current),
                after = Audit.minutes(minutes),
            )
            onGatedContentVisible()
        }
    }

    companion object {
        fun intent(context: Context, childId: DeviceId? = null) =
            RuleTargets.putExtra(Intent(context, AppLimitsActivity::class.java), childId)
    }
}
