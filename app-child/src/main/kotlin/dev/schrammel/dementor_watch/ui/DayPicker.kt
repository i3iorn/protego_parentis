package dev.schrammel.dementor_watch.ui

import android.app.Activity
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ToggleButton
import dev.schrammel.dementor_watch.R
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/**
 * Which days a rule runs on.
 *
 * Seven toggles rather than a list of checkboxes: a weekly schedule is read at a
 * glance as a shape, and "Mon Tue Wed Thu Fri" lit up says school nights in a way
 * that five ticked rows does not.
 *
 * Day names come from the JVM's locale data rather than from `strings.xml`. They
 * are already translated everywhere the app could ever ship, and hand-maintaining
 * seven names per language is seven chances to get one wrong.
 */
internal object DayPicker {

    fun build(
        activity: Activity,
        selected: Set<DayOfWeek>,
        onChanged: (Set<DayOfWeek>) -> Unit,
    ): LinearLayout {
        val chosen = selected.toMutableSet()

        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = activity.dimen(R.dimen.space_sm) }

            orderedDays().forEach { day ->
                val label = shortName(day)
                addView(
                    ToggleButton(context).apply {
                        text = label
                        textOn = label
                        textOff = label
                        isChecked = day in chosen
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f,
                        ).apply { marginEnd = activity.dimen(R.dimen.space_hairline) }
                        gravity = Gravity.CENTER
                        setOnCheckedChangeListener { _, checked ->
                            if (checked) chosen += day else chosen -= day
                            onChanged(chosen.toSet())
                        }
                    },
                )
            }
        }
    }

    /** A summary for a row: "Every day", "Weekdays", or the list. */
    fun describe(activity: Activity, days: Set<DayOfWeek>): String = when {
        days.isEmpty() -> activity.getString(R.string.days_none)
        days.size == DayOfWeek.entries.size -> activity.getString(R.string.days_every)
        days == WEEKDAYS -> activity.getString(R.string.days_weekdays)
        days == WEEKEND -> activity.getString(R.string.days_weekend)
        else -> orderedDays().filter { it in days }.joinToString(" ", transform = ::shortName)
    }

    /**
     * Monday first, always.
     *
     * Not the locale's first day: this is a Swedish and English app today, both of
     * which start the week on Monday, and a school-night bedtime reads wrong when
     * the row opens on Sunday.
     */
    private fun orderedDays(): List<DayOfWeek> = DayOfWeek.entries.toList()

    private fun shortName(day: DayOfWeek): String =
        day.getDisplayName(TextStyle.SHORT, Locale.getDefault())

    private val WEEKDAYS: Set<DayOfWeek> = setOf(
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY,
    )
    private val WEEKEND: Set<DayOfWeek> = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
}
