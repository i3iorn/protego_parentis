package dev.schrammel.dementor_watch.ui

import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import dev.schrammel.dementor_watch.R
import dev.schrammel.dementor_watch.audit.Audit
import dev.schrammel.dementor_watch.enforcement.EnforcementService
import dev.schrammel.dementor_watch.model.AuditAction
import dev.schrammel.dementor_watch.model.AuditActor
import dev.schrammel.dementor_watch.model.PackageName
import dev.schrammel.dementor_watch.rules.ScheduleStore
import dev.schrammel.dementor_watch.rules.StoredSchedule
import dev.schrammel.dementor_watch.usage.InstalledApps
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.UUID

/**
 * The family's named schedule windows (SC-2).
 *
 * Bedtime already existed as a single switch; this is everything else — school
 * hours, dinner, homework. Each window names itself, and the name is what appears
 * on the block screen, so "why is this blocked?" always has a specific answer
 * rather than a generic one.
 */
class SchedulesActivity : ParentGatedActivity() {

    private lateinit var store: ScheduleStore
    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ScheduleStore(this)
        column = contentColumn()
        showColumn(column)
    }

    override fun onGatedContentVisible() = render()

    override fun onGatedContentHidden() {
        column.removeAllViews()
    }

    private fun render() {
        column.removeAllViews()
        column.addView(screenTitle(getString(R.string.schedules_title)))
        column.addView(captionText(getString(R.string.schedules_hint)))

        val schedules = store.schedules.sortedBy { it.startMinuteOfDay }
        if (schedules.isEmpty()) {
            column.addView(bodyText(getString(R.string.schedules_empty)))
            // Offered rather than created. A family that opens this screen and
            // finds three windows already switched on has had rules made for them
            // by an app, which is the opposite of what this product is — so these
            // arrive off, named, and with the times a parent would have typed
            // anyway, leaving the decision where it belongs.
            column.addView(sectionHeader(getString(R.string.schedules_starters_header)))
            column.addView(captionText(getString(R.string.schedules_starters_hint)))
            column.addView(group(STARTERS.map(::starterRow)))
        } else {
            column.addView(group(schedules.map(::row)))
        }

        column.addView(
            actionButton().apply {
                setText(R.string.schedules_add)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dimen(R.dimen.space_md) }
                setOnClickListener { startActivity(ScheduleEditActivity.intent(context)) }
            },
        )
    }

    /**
     * A suggestion, which becomes a real schedule the moment it is tapped.
     *
     * Saved switched off and opened for editing straight away: the times here are
     * a guess at somebody else’s week, and the one thing worse than an empty
     * screen is a rule that started applying before anyone read it.
     */
    private fun starterRow(starter: Starter) = navigationRow(
        title = getString(starter.name),
        subtitle = getString(
            R.string.schedules_starter_detail,
            Format.time(java.time.LocalTime.of(starter.startHour, starter.startMinute)),
            Format.time(java.time.LocalTime.of(starter.endHour, starter.endMinute)),
        ),
    ) {
        val created = StoredSchedule(
            id = java.util.UUID.randomUUID().toString(),
            name = getString(starter.name),
            days = starter.days,
            startMinuteOfDay = starter.startHour * MINUTES_PER_HOUR + starter.startMinute,
            endMinuteOfDay = starter.endHour * MINUTES_PER_HOUR + starter.endMinute,
            enabled = false,
        )
        store.upsert(created)
        startActivity(ScheduleEditActivity.intent(this, created.id))
    }

    /** A named window a family is likely to want, with the hours already in it. */
    private data class Starter(
        val name: Int,
        val days: Set<Int>,
        val startHour: Int,
        val startMinute: Int,
        val endHour: Int,
        val endMinute: Int,
    )

    private fun row(schedule: StoredSchedule) = navigationRow(
        title = schedule.name.ifBlank { getString(R.string.schedules_unnamed) },
        subtitle = summarise(schedule),
    ) { startActivity(ScheduleEditActivity.intent(this, schedule.id)) }

    private fun summarise(schedule: StoredSchedule): String {
        val days = DayPicker.describe(
            this,
            schedule.days.mapNotNull { day -> DayOfWeek.entries.firstOrNull { it.value == day } }
                .toSet(),
        )
        val window = getString(
            R.string.schedules_window,
            Format.time(schedule.start),
            Format.time(schedule.end),
        )
        return if (schedule.enabled) {
            getString(R.string.schedules_row, days, window)
        } else {
            getString(R.string.schedules_row_off, days, window)
        }
    }

    companion object {
        private const val MINUTES_PER_HOUR = 60

        /** Monday to Friday, in ISO day numbers. */
        private val WEEKDAYS = setOf(1, 2, 3, 4, 5)

        /**
         * The three windows almost every family ends up describing.
         *
         * Times chosen to be plainly editable rather than plausibly correct —
         * nobody’s school day is exactly this, and the point is that the shape
         * is there and only the numbers need changing.
         */
        private val STARTERS = listOf(
            Starter(R.string.schedules_starter_school, WEEKDAYS, 8, 30, 15, 0),
            Starter(R.string.schedules_starter_homework, WEEKDAYS, 16, 0, 17, 30),
            Starter(R.string.schedules_starter_dinner, setOf(1, 2, 3, 4, 5, 6, 7), 18, 0, 19, 0),
        )

        fun intent(context: Context) = Intent(context, SchedulesActivity::class.java)
    }
}

/**
 * Editing one window.
 *
 * Saving is refused rather than silently corrected when the window is not one the
 * engine can accept — no name, no days, or a start equal to its end. A schedule
 * that quietly does nothing is worse than one that will not save.
 */
class ScheduleEditActivity : ParentGatedActivity() {

    private lateinit var store: ScheduleStore
    private lateinit var column: LinearLayout
    private lateinit var scheduleId: String

    private lateinit var nameInput: EditText
    private var days: Set<DayOfWeek> = emptySet()
    private var start: LocalTime = DEFAULT_START
    private var end: LocalTime = DEFAULT_END
    private var allowed: MutableSet<String> = mutableSetOf()
    private var enabled: Boolean = true

    private val pickAllowed = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            allowed = AppPickerActivity.resultFrom(result.data).toMutableSet()
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ScheduleStore(this)
        scheduleId = intent.getStringExtra(EXTRA_ID) ?: UUID.randomUUID().toString()

        store.find(scheduleId)?.let { existing ->
            days = existing.days
                .mapNotNull { day -> DayOfWeek.entries.firstOrNull { it.value == day } }
                .toSet()
            start = existing.start
            end = existing.end
            allowed = existing.allowedPackages.toMutableSet()
            enabled = existing.enabled
        }

        nameInput = EditText(this).apply {
            hint = getString(R.string.schedules_name_hint)
            setText(store.find(scheduleId)?.name.orEmpty())
        }

        column = contentColumn()
        showColumn(column)
    }

    override fun onGatedContentVisible() = render()

    override fun onGatedContentHidden() {
        column.removeAllViews()
    }

    private fun render() {
        column.removeAllViews()
        column.addView(screenTitle(getString(R.string.schedules_edit_title)))

        column.addView(sectionHeader(getString(R.string.schedules_name_header)))
        column.addView(nameInput)

        column.addView(sectionHeader(getString(R.string.schedules_days_header)))
        column.addView(DayPicker.build(this, days) { days = it })

        column.addView(sectionHeader(getString(R.string.schedules_window_header)))
        column.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    Button(context).apply {
                        text = getString(R.string.schedules_from, Format.time(start))
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f,
                        )
                        setOnClickListener {
                            pickTime(start) {
                                start = it
                                render()
                            }
                        }
                    },
                )
                addView(
                    Button(context).apply {
                        text = getString(R.string.schedules_to, Format.time(end))
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f,
                        )
                        setOnClickListener {
                            pickTime(end) {
                                end = it
                                render()
                            }
                        }
                    },
                )
            },
        )
        column.addView(captionText(getString(R.string.schedules_window_hint)))

        column.addView(sectionHeader(getString(R.string.schedules_allowed_header)))
        column.addView(captionText(getString(R.string.schedules_allowed_hint)))
        val summary = TextView(this).apply {
            styleAs(R.style.TextAppearance_Dementor_Body)
        }
        column.addView(summary)
        Background.load(this, {
            val apps = InstalledApps(this)
            allowed.map { apps.labelFor(PackageName(it)) }.sorted()
        }) { labels ->
            summary.text = labels.joinToString(", ")
                .ifBlank { getString(R.string.schedules_allowed_none) }
        }
        column.addView(
            actionButton().apply {
                setText(R.string.schedules_allowed_choose)
                setOnClickListener {
                    pickAllowed.launch(
                        AppPickerActivity.intent(
                            context,
                            getString(R.string.schedules_allowed_header),
                            allowed,
                        ),
                    )
                }
            },
        )

        column.addView(
            CheckBox(this).apply {
                setText(R.string.schedules_enabled)
                isChecked = enabled
                setOnCheckedChangeListener { _, checked -> enabled = checked }
            },
        )

        column.addView(
            actionButton().apply {
                setText(R.string.schedules_save)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dimen(R.dimen.space_md) }
                setOnClickListener { save() }
            },
        )

        if (store.find(scheduleId) != null) {
            column.addView(
                actionButton().apply {
                    setText(R.string.schedules_delete)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dimen(R.dimen.space_sm) }
                    setOnClickListener { delete() }
                },
            )
        }
    }

    private fun save() {
        val schedule = StoredSchedule(
            id = scheduleId,
            name = nameInput.text.toString().trim(),
            days = days.map { it.value }.toSet(),
            startMinuteOfDay = StoredSchedule.minuteOfDay(start),
            endMinuteOfDay = StoredSchedule.minuteOfDay(end),
            allowedPackages = allowed.toSet(),
            enabled = enabled,
        )

        val problem = when {
            schedule.name.isBlank() -> R.string.schedules_needs_name
            schedule.days.isEmpty() -> R.string.schedules_needs_days
            schedule.startMinuteOfDay == schedule.endMinuteOfDay -> R.string.schedules_needs_window
            else -> null
        }
        if (problem != null) {
            Toast.makeText(this, problem, Toast.LENGTH_SHORT).show()
            return
        }

        store.upsert(schedule)
        Audit.record(
            context = this,
            actor = AuditActor.PARENT,
            action = AuditAction.SCHEDULE_SAVED,
            subject = schedule.name,
            after = Audit.timeRange(schedule.start, schedule.end),
        )
        // A window may be starting right now; applying at once avoids a phone that
        // stays open until the next tick happens to notice.
        EnforcementService.start(this)
        finish()
    }

    private fun delete() {
        val name = store.find(scheduleId)?.name.orEmpty()
        store.delete(scheduleId)
        Audit.record(
            context = this,
            actor = AuditActor.PARENT,
            action = AuditAction.SCHEDULE_DELETED,
            subject = name,
        )
        EnforcementService.start(this)
        finish()
    }

    private fun pickTime(current: LocalTime, onPicked: (LocalTime) -> Unit) {
        TimePickerDialog(
            this,
            { _, hour, minute -> onPicked(LocalTime.of(hour, minute)) },
            current.hour,
            current.minute,
            true,
        ).show()
    }

    companion object {
        private const val EXTRA_ID = "scheduleId"
        private val DEFAULT_START: LocalTime = LocalTime.of(8, 0)
        private val DEFAULT_END: LocalTime = LocalTime.of(15, 0)

        fun intent(context: Context, scheduleId: String? = null) =
            Intent(context, ScheduleEditActivity::class.java).apply {
                scheduleId?.let { putExtra(EXTRA_ID, it) }
            }
    }
}
