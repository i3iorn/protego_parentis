package dev.schrammel.dementor_watch.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import dev.schrammel.dementor_watch.R
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
        apps = InstalledApps(this).launchable()
        setContentView(buildLayout())
    }

    override fun onGatedContentVisible() {
        render()
    }

    private fun buildLayout(): ScrollView {
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dimen(R.dimen.screen_padding)
            setPadding(pad, pad, pad, pad)
        }
        return ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            addView(container)
        }
    }

    private fun render() {
        container.removeAllViews()
        val limits = store.appLimits

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
                    text = if (limitMinutes > 0) Format.limit(limitMinutes) else getString(R.string.app_limits_none)
                    styleAs(R.style.TextAppearance_Dementor_Body)
                },
            )

            setOnClickListener { promptForLimit(app, limitMinutes) }
        }

    private fun promptForLimit(app: AppInfo, current: Int) {
        val value = TextView(this).apply {
            styleAs(R.style.TextAppearance_Dementor_Metric)
            text = Format.limit(current)
        }
        val bar = SeekBar(this).apply {
            max = LimitSlider.MAX_STEPS
            progress = LimitSlider.progressFor(current)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    value.text = Format.limit(LimitSlider.minutesFor(progress))
                }

                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dimen(R.dimen.screen_padding)
            setPadding(pad, pad, pad, 0)
            addView(value)
            addView(bar)
        }

        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setView(content)
            .setPositiveButton(R.string.app_limits_save) { _, _ ->
                store.setAppLimit(app.pkg.value, LimitSlider.minutesFor(bar.progress))
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        fun intent(context: Context) = Intent(context, AppLimitsActivity::class.java)
    }
}
