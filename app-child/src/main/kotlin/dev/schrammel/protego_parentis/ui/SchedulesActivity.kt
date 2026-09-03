package dev.schrammel.protego_parentis.ui

import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.audit.Audit
import dev.schrammel.protego_parentis.model.AuditAction
import dev.schrammel.protego_parentis.model.AuditActor
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.ScheduleBlock
import dev.schrammel.protego_parentis.rules.ScheduleStore
import dev.schrammel.protego_parentis.rules.StoredSchedule
import dev.schrammel.protego_parentis.rules.toMinuteOfDay
import dev.schrammel.protego_parentis.sync.DeviceId
import dev.schrammel.protego_parentis.ui.rules.appCatalog
import dev.schrammel.protego_parentis.ui.rules.delete
import dev.schrammel.protego_parentis.ui.rules.find
import dev.schrammel.protego_parentis.ui.rules.labelFor
import dev.schrammel.protego_parentis.ui.rules.scheduleRuleStore
import dev.schrammel.protego_parentis.ui.rules.upsert
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.UUID

/**
 * The family's named schedule windows (SC-2), on whichever kind of phone
 * edits [target]'s rules (#43).
 *
 * Bedtime already existed as a single switch; this is everything else — school
 * hours, dinner, homework. Each window names itself, and the name is what appears
 * on the block screen, so "why is this blocked?" always has a specific answer
 * rather than a generic one.
 */
class SchedulesActivity : ParentGatedActivity() {

    private lateinit var target: RuleTarget
    private lateinit var column: LinearLayout

    override fun supportsReadOnly() = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        column = contentColumn()
        showColumn(column)
    }

    override fun onGatedContentVisible() {
        val resolved = RuleTargets.resolve(this) ?: return finish()
        target = resolved
        render()
    }

    override fun onGatedContentHidden() {
        column.removeAllViews()
    }

    private fun render() {
        column.removeAllViews()
        column.addView(screenTitle(getString(R.string.schedules_title)))
        column.addView(captionText(getString(R.string.schedules_hint)))
        if (!canEdit) column.addView(readOnlyBanner { requestUnlock { onGatedContentVisible() } })

        // Bedtime lives in the same store now (see ScheduleStore's own doc
        // comment) but keeps its own switch on the limits screen — this list
        // is the generic add/delete flow bedtime is deliberately kept out of.
        val schedules = scheduleRuleStore(this, target).read()
            .filterNot { it.id == ScheduleStore.BEDTIME_ID }
            .sortedBy { it.startMinuteOfDay }
        if (schedules.isEmpty()) {
            column.addView(bodyText(getString(R.string.schedules_empty)))
            if (canEdit) {
                // Offered rather than created. A family that opens this screen and
                // finds three windows already switched on has had rules made for
                // them by an app, which is the opposite of what this product is —
                // so these arrive off, named, and with the times a parent would
                // have typed anyway, leaving the decision where it belongs.
                column.addView(sectionHeader(getString(R.string.schedules_starters_header)))
                column.addView(captionText(getString(R.string.schedules_starters_hint)))
                column.addView(group(STARTERS.map(::starterRow)))
            }
        } else {
            column.addView(group(schedules.map(::row)))
        }

        if (canEdit) {
            column.addView(
                actionButton().apply {
                    setText(R.string.schedules_add)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dimen(R.dimen.space_md) }
                    setOnClickListener { startActivity(ScheduleEditActivity.intent(context, childId = target.childId)) }
                },
            )
        }
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
            formatTime(LocalTime.of(starter.startHour, starter.startMinute)),
            formatTime(LocalTime.of(starter.endHour, starter.endMinute)),
        ),
    ) {
        val created = StoredSchedule(
            id = UUID.randomUUID().toString(),
            name = getString(starter.name),
            days = starter.days,
            startMinuteOfDay = LocalTime.of(starter.startHour, starter.startMinute).toMinuteOfDay(),
            endMinuteOfDay = LocalTime.of(starter.endHour, starter.endMinute).toMinuteOfDay(),
            enabled = false,
        )
        scheduleRuleStore(this, target).upsert(created)
        startActivity(ScheduleEditActivity.intent(this, created.id, target.childId))
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
    ) { startActivity(ScheduleEditActivity.intent(this, schedule.id, target.childId)) }

    private fun summarise(schedule: StoredSchedule): String {
        val days = DayPicker.describe(
            this,
            schedule.days.mapNotNull { day -> DayOfWeek.entries.firstOrNull { it.value == day } }
                .toSet(),
        )
        val window = getString(
            R.string.schedules_window,
            formatTime(schedule.start),
            formatTime(schedule.end),
        )
        return if (schedule.enabled) {
            getString(R.string.schedules_row, days, window)
        } else {
            getString(R.string.schedules_row_off, days, window)
        }
    }

    companion object {
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

        fun intent(context: Context, childId: DeviceId? = null) =
            RuleTargets.putExtra(Intent(context, SchedulesActivity::class.java), childId)
    }
}

/**
 * Editing one window, on whichever kind of phone edits [target]'s rules (#43).
 *
 * Saving is refused rather than silently corrected when the window is not one the
 * engine can accept — no name, no days, or a start equal to its end. A schedule
 * that quietly does nothing is worse than one that will not save.
 */
class ScheduleEditActivity : ParentGatedActivity() {

    private lateinit var target: RuleTarget
    private lateinit var column: LinearLayout
    private lateinit var scheduleId: String
    private var existing: StoredSchedule? = null

    private lateinit var nameInput: EditText
    private var days: Set<DayOfWeek> = emptySet()
    private var start: LocalTime = DEFAULT_START
    private var end: LocalTime = DEFAULT_END
    private var allowed: MutableSet<String> = mutableSetOf()
    private var mode: ScheduleBlock.Mode = ScheduleBlock.Mode.EXCLUSIVE
    private var enabled: Boolean = true

    private val pickAllowed = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            allowed = AppPickerActivity.resultFrom(result.data).toMutableSet()
            render()
        }
    }

    override fun supportsReadOnly() = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val resolved = RuleTargets.resolve(this) ?: return finish()
        target = resolved
        scheduleId = intent.getStringExtra(EXTRA_ID) ?: UUID.randomUUID().toString()

        val found = scheduleRuleStore(this, target).find(scheduleId)
        existing = found
        found?.let { ex ->
            days = ex.days
                .mapNotNull { day -> DayOfWeek.entries.firstOrNull { it.value == day } }
                .toSet()
            start = ex.start
            end = ex.end
            allowed = ex.packages.toMutableSet()
            mode = ex.mode
            enabled = ex.enabled
        }

        nameInput = EditText(this).apply {
            hint = getString(R.string.schedules_name_hint)
            setText(found?.name.orEmpty())
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
        if (!canEdit) column.addView(readOnlyBanner { requestUnlock { onGatedContentVisible() } })

        column.addView(sectionHeader(getString(R.string.schedules_name_header)))
        nameInput.isEnabled = canEdit
        column.addView(nameInput)

        column.addView(sectionHeader(getString(R.string.schedules_days_header)))
        column.addView(DayPicker.build(this, days) { days = it }.apply { setChildrenEnabled(canEdit) })

        column.addView(sectionHeader(getString(R.string.schedules_window_header)))
        column.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    Button(context).apply {
                        text = getString(R.string.schedules_from, formatTime(start))
                        isEnabled = canEdit
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
                        text = getString(R.string.schedules_to, formatTime(end))
                        isEnabled = canEdit
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

        // The mode comes before the list, because it decides what the list means.
        // Choosing apps first and the reading afterwards is how somebody ends up
        // having picked the exact opposite of what they wanted.
        column.addView(sectionHeader(getString(R.string.schedules_mode_header)))
        column.addView(
            RadioGroup(this).apply {
                orientation = RadioGroup.VERTICAL
                isEnabled = canEdit
                addView(
                    RadioButton(context).apply {
                        id = MODE_EXCLUSIVE_ID
                        setText(R.string.schedules_mode_exclusive)
                        isEnabled = canEdit
                    },
                )
                addView(
                    RadioButton(context).apply {
                        id = MODE_INCLUSIVE_ID
                        setText(R.string.schedules_mode_inclusive)
                        isEnabled = canEdit
                    },
                )
                check(
                    when (mode) {
                        ScheduleBlock.Mode.EXCLUSIVE -> MODE_EXCLUSIVE_ID
                        ScheduleBlock.Mode.INCLUSIVE -> MODE_INCLUSIVE_ID
                    },
                )
                setOnCheckedChangeListener { _, checked ->
                    val chosen = if (checked == MODE_INCLUSIVE_ID) {
                        ScheduleBlock.Mode.INCLUSIVE
                    } else {
                        ScheduleBlock.Mode.EXCLUSIVE
                    }
                    if (chosen == mode) return@setOnCheckedChangeListener
                    mode = chosen
                    // The chosen apps are kept. They are the same apps either
                    // way; only what the schedule does with them changes, and
                    // clearing them would punish somebody for reading the labels
                    // and correcting themselves.
                    render()
                }
            },
        )

        val including = mode == ScheduleBlock.Mode.INCLUSIVE
        column.addView(
            sectionHeader(
                getString(
                    if (including) R.string.schedules_apps_paused_header
                    else R.string.schedules_allowed_header,
                ),
            ),
        )
        column.addView(
            captionText(
                getString(
                    if (including) R.string.schedules_apps_paused_hint
                    else R.string.schedules_allowed_hint,
                ),
            ),
        )
        val summary = TextView(this).apply {
            styleAs(R.style.TextAppearance_Dementor_Body)
        }
        column.addView(summary)
        Background.load(this, {
            val catalog = appCatalog(this, target)
            allowed.map { catalog.labelFor(PackageName(it)) }.sorted()
        }) { labels ->
            summary.text = labels.joinToString(", ").ifBlank {
                getString(
                    if (including) R.string.schedules_apps_paused_none
                    else R.string.schedules_allowed_none,
                )
            }
        }
        if (canEdit) {
            column.addView(
                actionButton().apply {
                    setText(R.string.schedules_allowed_choose)
                    setOnClickListener {
                        pickAllowed.launch(
                            AppPickerActivity.intent(
                                context,
                                getString(
                                    if (including) R.string.schedules_apps_paused_header
                                    else R.string.schedules_allowed_header,
                                ),
                                allowed,
                                target.childId,
                            ),
                        )
                    }
                },
            )
        }

        column.addView(
            CheckBox(this).apply {
                setText(R.string.schedules_enabled)
                isChecked = enabled
                isEnabled = canEdit
                setOnCheckedChangeListener { _, checked -> enabled = checked }
            },
        )

        if (canEdit) {
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

            if (existing != null) {
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
    }

    private fun save() {
        val schedule = StoredSchedule(
            id = scheduleId,
            name = nameInput.text.toString().trim(),
            days = days.map { it.value }.toSet(),
            startMinuteOfDay = start.toMinuteOfDay(),
            endMinuteOfDay = end.toMinuteOfDay(),
            packages = allowed.toSet(),
            mode = mode,
            enabled = enabled,
        )

        val problem = when {
            schedule.name.isBlank() -> R.string.schedules_needs_name
            schedule.days.isEmpty() -> R.string.schedules_needs_days
            schedule.startMinuteOfDay == schedule.endMinuteOfDay -> R.string.schedules_needs_window
            // Saving this would produce a window that pauses nothing, which
            // looks from the list exactly like one that works.
            schedule.mode == ScheduleBlock.Mode.INCLUSIVE && schedule.packages.isEmpty() ->
                R.string.schedules_needs_apps
            else -> null
        }
        if (problem != null) {
            toast(problem)
            return
        }

        // A window may be starting right now; the store's own write already
        // re-runs enforcement at once on the managed phone (LocalScheduleRuleStore),
        // so there is nothing further to trigger here — and nothing to trigger
        // at all on a parent's phone editing someone else's schedules.
        scheduleRuleStore(this, target).upsert(schedule)
        Audit.record(
            context = this,
            actor = AuditActor.PARENT,
            action = AuditAction.SCHEDULE_SAVED,
            subject = schedule.name,
            after = Audit.timeRange(schedule.start, schedule.end),
        )
        finish()
    }

    private fun delete() {
        val name = existing?.name.orEmpty()
        scheduleRuleStore(this, target).delete(scheduleId)
        Audit.record(
            context = this,
            actor = AuditActor.PARENT,
            action = AuditAction.SCHEDULE_DELETED,
            subject = name,
        )
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

        // Fixed ids because the radios are rebuilt on every render and the group
        // has to be able to say which one is checked across those rebuilds.
        private const val MODE_EXCLUSIVE_ID = 1
        private const val MODE_INCLUSIVE_ID = 2

        private val DEFAULT_START: LocalTime = LocalTime.of(8, 0)
        private val DEFAULT_END: LocalTime = LocalTime.of(15, 0)

        fun intent(context: Context, scheduleId: String? = null, childId: DeviceId? = null) =
            RuleTargets.putExtra(
                Intent(context, ScheduleEditActivity::class.java).apply {
                    scheduleId?.let { putExtra(EXTRA_ID, it) }
                },
                childId,
            )
    }
}
