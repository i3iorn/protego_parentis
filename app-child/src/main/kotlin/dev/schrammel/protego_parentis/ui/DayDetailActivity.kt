package dev.schrammel.protego_parentis.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.enforcement.EnforcementCoordinator
import dev.schrammel.protego_parentis.format.formatDuration
import dev.schrammel.protego_parentis.model.UsageSnapshot
import dev.schrammel.protego_parentis.policy.usage.UsageAggregates
import dev.schrammel.protego_parentis.usage.InstalledApps
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.time.Duration

class DayDetailActivity : DementorActivity() {
    override val showsBackButton = true

    private lateinit var coordinator: EnforcementCoordinator
    private lateinit var installedApps: InstalledApps

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        coordinator = EnforcementCoordinator(this)
        installedApps = InstalledApps(this)

        // Use @Suppress to avoid deprecation warning from getSerializableExtra
        @Suppress("DEPRECATION")
        val targetDay = intent.getSerializableExtra(EXTRA_DAY) as? LocalDate
            ?: return
        val column = contentColumn()

        val history = coordinator.historyIncludingToday()
        val day = history.find { it.policyDay == targetDay } ?: UsageSnapshot.empty(targetDay)

        column.addView(screenTitle(formatDayLabel(day.policyDay)))
        column.addView(bodyText(formatDuration(day.deviceTotal) + " " + getString(R.string.day_detail_total)))
        column.addView(title(getString(R.string.day_detail_by_app), 20f))
        val sorted = day.byPackage.toList().sortedByDescending { it.second }
        if (sorted.isEmpty()) {
            column.addView(bodyText(getString(R.string.day_detail_none)))
        } else {
            sorted.forEach { (pkg, time) ->
                // labelFor always returns a non-null String, so elvis is unnecessary
                column.addView(statRow(installedApps.labelFor(pkg), formatDuration(time)))
            }
        }
        showColumn(column)
    }

    private fun formatDayLabel(date: LocalDate?): String = date?.let {
        val weekday = it.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        val month = it.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        "$weekday ${it.dayOfMonth} $month"
    } ?: getString(R.string.day_detail_unknown)

    private fun title(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        setPadding(0, dimen(R.dimen.space_xl), 0, dimen(R.dimen.space_xxs))
    }

    companion object {
        const val EXTRA_DAY = "day"
        fun intent(context: Context, day: LocalDate) =
            Intent(context, DayDetailActivity::class.java).apply {
                putExtra(EXTRA_DAY, day)
            }
    }
}