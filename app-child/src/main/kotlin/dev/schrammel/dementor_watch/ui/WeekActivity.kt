package dev.schrammel.dementor_watch.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import dev.schrammel.dementor_watch.R
import dev.schrammel.dementor_watch.enforcement.EnforcementCoordinator
import dev.schrammel.dementor_watch.model.UsageSnapshot
import dev.schrammel.dementor_watch.policy.usage.UsageAggregates
import dev.schrammel.dementor_watch.usage.InstalledApps
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
            container.addView(body(getString(R.string.week_no_data)))
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
        container.addView(body(getString(R.string.week_total_caption)))
        container.addView(
            body(getString(R.string.week_average, formatDuration(summary.dailyAverage))),
        )
        container.addView(deltaLine(UsageAggregates.delta(thisWeek, lastWeek), lastWeek))

        container.addView(title(getString(R.string.week_by_day), 20f))
        renderBars(thisWeek)

        container.addView(title(getString(R.string.week_top_apps), 20f))
        if (summary.topApps.isEmpty()) {
            container.addView(body(getString(R.string.week_no_apps)))
        } else {
            summary.topApps.forEach { app ->
                container.addView(
                    row(
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
    private fun renderBars(days: List<UsageSnapshot>) {
        val busiest = days.maxOfOrNull { it.deviceTotal } ?: Duration.ZERO

        days.forEach { day ->
            val fraction = if (busiest > Duration.ZERO) {
                day.deviceTotal.inWholeSeconds.toFloat() / busiest.inWholeSeconds.toFloat()
            } else {
                0f
            }

            container.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dimen(R.dimen.space_xxs), 0, dimen(R.dimen.space_xxs))

                    addView(
                        TextView(context).apply {
                            text = shortDayName(day.policyDay)
                            styleAs(R.style.TextAppearance_Dementor_Caption)
                            layoutParams = LinearLayout.LayoutParams(dimen(R.dimen.week_day_label_width), WRAP)
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
        return body(text)
    }

    private fun shortDayName(date: LocalDate): String =
        date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())

    private fun title(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        setPadding(0, dimen(R.dimen.space_xl), 0, dimen(R.dimen.space_xxs))
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        styleAs(R.style.TextAppearance_Dementor_Body)
        setPadding(0, dimen(R.dimen.space_hairline), 0, dimen(R.dimen.space_hairline))
    }

    private fun row(label: String, trailing: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dimen(R.dimen.space_xs), 0, dimen(R.dimen.space_xs))
        addView(
            TextView(context).apply {
                text = label
                styleAs(R.style.TextAppearance_Dementor_Body)
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            },
        )
        addView(TextView(context).apply { text = trailing; styleAs(R.style.TextAppearance_Dementor_Body) })
    }

    companion object {
        fun intent(context: Context) = Intent(context, WeekActivity::class.java)
    }
}
