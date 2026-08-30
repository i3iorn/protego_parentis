package dev.schrammel.dementor_watch.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.schrammel.dementor_watch.R
import dev.schrammel.dementor_watch.audit.Audit
import dev.schrammel.dementor_watch.model.AuditAction
import dev.schrammel.dementor_watch.model.AuditActor
import dev.schrammel.dementor_watch.rules.LocalPolicyStore
import dev.schrammel.dementor_watch.usage.AppInfo
import dev.schrammel.dementor_watch.usage.InstalledApps

/**
 * A daily limit for one individual app.
 *
 * Separate from categories on purpose: a category answers "an hour of games in
 * total", this answers "and no more than twenty minutes of that one". Both apply,
 * and whichever runs out first is what blocks.
 */
class AppLimitsActivity : ParentGatedActivity() {

    private lateinit var store: LocalPolicyStore
    private lateinit var container: LinearLayout
    private var apps: List<AppInfo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = LocalPolicyStore(this)
        container = contentColumn()
        showColumn(container)
    }

    override fun onGatedContentVisible() {
        Background.load(this, {
            // Both the app list and the stored limits come off disk.
            InstalledApps(this).launchable() to store.appLimits
        }) { (loaded, limits) ->
            apps = loaded
            render(limits)
        }
    }

    private fun render(limits: Map<String, Int>) {
        container.removeAllViews()

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

        // Apps that already have a limit float to the top, so the ones the family
        // actually cares about are not buried in an alphabetical list of hundreds.
        val (limited, unlimited) = apps.partition { limits.containsKey(it.pkg.value) }
        (limited + unlimited).forEach { app ->
            container.addView(row(app, limits[app.pkg.value] ?: 0))
        }
    }

    private fun row(app: AppInfo, limitMinutes: Int): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dimen(R.dimen.space_sm), 0, dimen(R.dimen.space_sm))
            isClickable = true

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

            setOnClickListener { promptForLimit(app, limitMinutes) }
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
            store.setAppLimit(app.pkg.value, minutes)
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
        fun intent(context: Context) = Intent(context, AppLimitsActivity::class.java)
    }
}
