package dev.schrammel.protego_parentis.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.format.formatDuration
import dev.schrammel.protego_parentis.enforcement.EnforcementCoordinator
import dev.schrammel.protego_parentis.model.UsageSnapshot
import dev.schrammel.protego_parentis.policy.usage.UsageAggregates
import dev.schrammel.protego_parentis.usage.InstalledApps
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.time.Duration

/**
 * "My week" — the teenager's own trends (RPT-2, teen side).
 *
 * The same numbers a parent would see, framed for self-management rather than
 * for supervision. Seven days is deliberate: long enough to show a pattern,
 * short enough that a bad Tuesday does not feel permanent.
 */
class WeekActivity : DementorActivity() {

    private lateinit var coordinator: EnforcementCoordinator
    private lateinit var installedApps: InstalledApps
    private lateinit var container: LinearLayout

    /** See `res/values/config.xml`. */
    private val minimumBarFraction: Float by lazy {
        ResourcesCompat.getFloat(resources, R.dimen.week_bar_minimum_fraction)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        coordinator = EnforcementCoordinator(this)
        installedApps = InstalledApps(this)

        container = contentColumn()
        showColumn(container)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        container.removeAllViews()
        container.addView(title(getString(R.string.week_title), 30f))
        Background.load(this, { coordinator.historyIncludingToday() }, ::show)
    }

    private fun show(recorded: List<UsageSnapshot>) {
        if (recorded.isEmpty()) {
            container.addView(bodyText(getString(R.string.week_no_data)))
            return
        }

        val today = recorded.maxOf { it.policyDay }
        val week = resources.getInteger(R.integer.week_length_days).toLong()
        val thisWeek = UsageAggregates.fill(recorded, today.minusDays(week - 1), today)
        val lastWeek = UsageAggregates.fill(
            recorded,
            today.minusDays(week * 2 - 1),
            today.minusDays(week),
        )

        val summary = UsageAggregates.summarise(
            thisWeek,
            topAppLimit = resources.getInteger(R.integer.week_top_apps),
        )

        container.addView(title(formatDuration(summary.total), 34f))
        container.addView(bodyText(getString(R.string.week_total_caption)))
        container.addView(
            bodyText(getString(R.string.week_average, formatDuration(summary.dailyAverage))),
        )
        container.addView(deltaLine(UsageAggregates.delta(thisWeek, lastWeek), lastWeek))

        container.addView(title(getString(R.string.week_by_day), 20f))
        renderBars(thisWeek, today)

        container.addView(title(getString(R.string.week_top_apps), 20f))
        if (summary.topApps.isEmpty()) {
            container.addView(bodyText(getString(R.string.week_no_apps)))
        } else {
            summary.topApps.forEach { app ->
                container.addView(
                    statRow(
                        installedApps.labelFor(app.pkg),
                        formatDuration(app.total),
                    ),
                )
            }
        }
    }

    /**
     * A bar per day, sized against the busiest day rather than against the limit —
     * the question this answers is "which days were heavy", not "did I comply".
     */
    private fun renderBars(days: List<UsageSnapshot>, today: LocalDate) {
        val busiest = days.maxOfOrNull { it.deviceTotal } ?: Duration.ZERO

        days.forEach { day ->
            val fraction = if (busiest > Duration.ZERO) {
                day.deviceTotal.inWholeSeconds.toFloat() / busiest.inWholeSeconds.toFloat()
            } else {
                0f
            }
            val isToday = day.policyDay == today

            container.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dimen(R.dimen.space_xxs), 0, dimen(R.dimen.space_xxs))

                    addView(
                        TextView(context).apply {
                            // The weekday name alone repeats every seven rows —
                            // without a date next to it, a reader has no way to
                            // tell this "Mon" from the "Mon" a week further down,
                            // or to know which one is actually today.
                            text = dayLabel(day.policyDay)
                            styleAs(R.style.TextAppearance_Dementor_Caption)
                            layoutParams = LinearLayout.LayoutParams(dimen(R.dimen.week_day_label_width), WRAP)
                            if (isToday) setTypeface(typeface, android.graphics.Typeface.BOLD)
                        },
                    )
                    // A track holding a proportionally sized fill, so the bar
                    // scales with the screen instead of a fixed pixel width.
                    addView(
                        LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            layoutParams = LinearLayout.LayoutParams(0, dimen(R.dimen.week_bar_height), 1f)
                                .apply { marginEnd = dimen(R.dimen.space_md) }
                            setBackgroundResource(R.drawable.week_bar_track)

                            addView(
                                View(context).apply {
                                    setBackgroundResource(R.drawable.week_bar_fill)
                                    layoutParams = LinearLayout.LayoutParams(
                                        0,
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        fraction.coerceAtLeast(minimumBarFraction),
                                    )
                                },
                            )
                            // Remainder of the track, left blank.
                            addView(
                                View(context).apply {
                                    layoutParams = LinearLayout.LayoutParams(
                                        0,
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        (1f - fraction).coerceAtLeast(0f),
                                    )
                                },
                            )
                        },
                    )
                    addView(
                        TextView(context).apply {
                            text = if (day.deviceTotal > Duration.ZERO) {
                                formatDuration(day.deviceTotal)
                            } else {
                                getString(R.string.week_none)
                            }
                            styleAs(R.style.TextAppearance_Dementor_Caption)
                        },
                    )
                },
            )
        }
    }

    private fun deltaLine(delta: Duration, previous: List<UsageSnapshot>): TextView {
        val hadPrevious = previous.any { it.deviceTotal > Duration.ZERO }
        val text = when {
            !hadPrevious -> getString(R.string.week_no_comparison)
            delta > Duration.ZERO ->
                getString(R.string.week_more_than_last, formatDuration(delta))

            delta < Duration.ZERO ->
                getString(R.string.week_less_than_last, formatDuration(-delta))

            else -> getString(R.string.week_same_as_last)
        }
        return bodyText(text)
    }

    private fun dayLabel(date: LocalDate): String = getString(
        R.string.week_day_label,
        date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
        date.dayOfMonth,
    )

    private fun title(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        setPadding(0, dimen(R.dimen.space_xl), 0, dimen(R.dimen.space_xxs))
    }

    companion object {
        fun intent(context: Context) = Intent(context, WeekActivity::class.java)
    }
}
