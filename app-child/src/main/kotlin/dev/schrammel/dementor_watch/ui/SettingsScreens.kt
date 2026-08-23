package dev.schrammel.dementor_watch.ui

import android.app.TimePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import dev.schrammel.dementor_watch.R
import dev.schrammel.dementor_watch.enforcement.EnforcementCoordinator
import dev.schrammel.dementor_watch.enforcement.EnforcementService
import dev.schrammel.dementor_watch.model.PackageName
import dev.schrammel.dementor_watch.rules.GoalCoordinator
import dev.schrammel.dementor_watch.rules.LocalPolicyStore
import dev.schrammel.dementor_watch.usage.InstalledApps
import java.time.LocalTime

/**
 * How much, and when.
 *
 * The daily allowance, the apps that ignore it, and the hours the phone stops
 * being interesting. These belong together because they are the three answers to
 * one question, and were previously separated by four other sections.
 */
class LimitsActivity : ParentGatedActivity() {

    private lateinit var store: LocalPolicyStore
    private lateinit var column: LinearLayout
    private lateinit var exemptSummary: TextView

    private val pickExempt = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            store.exemptPackages = AppPickerActivity.resultFrom(result.data).toSet()
            renderExempt()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = LocalPolicyStore(this)
        column = contentColumn()
        showColumn(column)
    }

    override fun onGatedContentVisible() = render()

    private fun render() {
        column.removeAllViews()
        column.addView(screenTitle(getString(R.string.limits_title)))

        column.addView(sectionHeader(getString(R.string.rules_daily_limit_header)))
        column.addView(captionText(getString(R.string.rules_daily_limit_hint)))
        column.addView(limitSlider())

        column.addView(sectionHeader(getString(R.string.rules_bedtime_header)))
        column.addView(bedtime())

        column.addView(sectionHeader(getString(R.string.rules_exempt_header)))
        column.addView(captionText(getString(R.string.rules_exempt_hint)))
        exemptSummary = bodyText("")
        column.addView(exemptSummary)
        column.addView(
            Button(this).apply {
                setText(R.string.rules_choose_exempt)
                setOnClickListener {
                    pickExempt.launch(
                        AppPickerActivity.intent(
                            this@LimitsActivity,
                            getString(R.string.rules_exempt_header),
                            store.exemptPackages,
                        ),
                    )
                }
            },
        )
        renderExempt()
    }

    private fun limitSlider(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL

        val value = TextView(context).apply {
            styleAs(R.style.TextAppearance_Dementor_Metric)
            text = Format.limit(store.dailyLimitMinutes)
        }
        addView(value)
        addView(
            SeekBar(context).apply {
                max = LimitSlider.MAX_STEPS
                progress = LimitSlider.progressFor(store.dailyLimitMinutes)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                        val minutes = LimitSlider.minutesFor(progress)
                        value.text = Format.limit(minutes)
                        if (fromUser) store.dailyLimitMinutes = minutes
                    }

                    override fun onStartTrackingTouch(bar: SeekBar) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar) = Unit
                })
            },
        )
    }

    private fun bedtime(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL

        val times = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val start = Button(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        val end = Button(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        times.addView(start)
        times.addView(end)

        val toggle = CheckBox(context).apply { setText(R.string.rules_bedtime_enable) }

        fun refresh() {
            val setting = store.bedtime
            toggle.isChecked = setting.enabled
            times.visibility = if (setting.enabled) View.VISIBLE else View.GONE
            start.text = getString(R.string.rules_bedtime_start, Format.time(setting.start))
            end.text = getString(R.string.rules_bedtime_end, Format.time(setting.end))
        }

        toggle.setOnCheckedChangeListener { _, checked ->
            store.bedtime = store.bedtime.copy(enabled = checked)
            refresh()
        }
        start.setOnClickListener {
            pickTime(store.bedtime.start) {
                store.bedtime = store.bedtime.copy(start = it)
                refresh()
            }
        }
        end.setOnClickListener {
            pickTime(store.bedtime.end) {
                store.bedtime = store.bedtime.copy(end = it)
                refresh()
            }
        }

        addView(toggle)
        addView(times)
        refresh()
    }

    private fun renderExempt() {
        Background.load(this, {
            val apps = InstalledApps(this)
            store.exemptPackages.map { apps.labelFor(PackageName(it)) }.sorted()
        }) { labels ->
            exemptSummary.text = labels.joinToString(", ")
                .ifBlank { getString(R.string.rules_no_exempt) }
        }
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

    private companion object {
        const val WRAP_CONTENT = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
    }
}

/** The list of categories, with their limits and how many apps each holds. */
class CategoriesActivity : ParentGatedActivity() {

    private lateinit var store: LocalPolicyStore
    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = LocalPolicyStore(this)
        column = contentColumn()
        showColumn(column)
    }

    override fun onGatedContentVisible() = render()

    private fun render() {
        column.removeAllViews()
        column.addView(screenTitle(getString(R.string.rules_categories_header)))
        column.addView(captionText(getString(R.string.rules_categories_hint)))

        val categories = store.categories.sortedBy { it.name.lowercase() }
        if (categories.isEmpty()) {
            column.addView(bodyText(getString(R.string.rules_no_categories)))
        }
        categories.forEachIndexed { index, category ->
            if (index > 0) column.addView(divider())
            column.addView(
                navigationRow(
                    title = category.name,
                    subtitle = getString(
                        R.string.rules_category_row,
                        Format.limit(category.limitMinutes),
                        resources.getQuantityString(
                            R.plurals.rules_category_apps,
                            category.packages.size,
                            category.packages.size,
                        ),
                    ),
                ) { startActivity(CategoryEditActivity.intent(this, category.id)) },
            )
        }

        column.addView(
            Button(this).apply {
                setText(R.string.rules_add_category)
                setOnClickListener { startActivity(CategoryEditActivity.intent(this@CategoriesActivity)) }
            },
        )
    }
}

/** The daily goal and the prize for meeting it. */
class GoalActivity : ParentGatedActivity() {

    private lateinit var goals: GoalCoordinator
    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        goals = GoalCoordinator(this)
        column = contentColumn()
        showColumn(column)
    }

    override fun onGatedContentVisible() = render()

    private fun render() {
        column.removeAllViews()
        column.addView(screenTitle(getString(R.string.rules_goal_header)))
        column.addView(captionText(getString(R.string.rules_goal_hint)))

        val existing = goals.goal()
        val settings = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (existing.enabled) View.VISIBLE else View.GONE
        }

        val startingMinutes = existing.maxUsage.inWholeMinutes.toInt()
            .takeIf { it > 0 } ?: DEFAULT_GOAL_MINUTES
        val value = TextView(this).apply {
            styleAs(R.style.TextAppearance_Dementor_Metric)
            text = Format.limit(startingMinutes)
        }
        val bar = SeekBar(this).apply {
            max = LimitSlider.MAX_STEPS
            progress = LimitSlider.progressFor(startingMinutes)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    value.text = Format.limit(LimitSlider.minutesFor(progress))
                }

                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }
        val prize = EditText(this).apply {
            hint = getString(R.string.rules_goal_prize_hint)
            setText(existing.prize)
        }

        column.addView(
            CheckBox(this).apply {
                setText(R.string.rules_goal_enable)
                isChecked = existing.enabled
                setOnCheckedChangeListener { _, checked ->
                    settings.visibility = if (checked) View.VISIBLE else View.GONE
                    // Switching off applies at once. Switching on waits for a prize,
                    // so a goal is never live with nothing to win.
                    if (!checked) {
                        goals.saveGoal(false, LimitSlider.minutesFor(bar.progress), prize.text.toString())
                    }
                }
            },
        )
        settings.addView(value)
        settings.addView(bar)
        settings.addView(prize)
        settings.addView(
            Button(this).apply {
                setText(R.string.rules_goal_save)
                setOnClickListener {
                    val text = prize.text.toString().trim()
                    if (text.isEmpty()) {
                        Toast.makeText(context, R.string.rules_goal_needs_prize, Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    goals.saveGoal(true, LimitSlider.minutesFor(bar.progress), text)
                    Toast.makeText(context, R.string.rules_goal_saved, Toast.LENGTH_SHORT).show()
                }
            },
        )
        column.addView(settings)
    }

    private companion object {
        const val DEFAULT_GOAL_MINUTES = 60
    }
}

/** The PIN, the lockdown switch, and the way back out. */
class DeviceControlActivity : ParentGatedActivity() {

    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        column = contentColumn()
        showColumn(column)
    }

    override fun onGatedContentVisible() = render()

    private fun render() {
        val store = LocalPolicyStore(this)
        val coordinator = EnforcementCoordinator(this)

        column.removeAllViews()
        column.addView(screenTitle(getString(R.string.rules_device_header)))

        column.addView(sectionHeader(getString(R.string.rules_security_header)))
        column.addView(
            Button(this).apply {
                setText(R.string.rules_change_pin)
                setOnClickListener {
                    // The old PIN is verified inside, and replaced only once the new
                    // one is confirmed.
                    startActivity(PinActivity.intent(this@DeviceControlActivity, change = true))
                }
            },
        )

        column.addView(sectionHeader(getString(R.string.rules_tamper_enable)))
        column.addView(captionText(getString(R.string.rules_tamper_hint)))
        column.addView(
            CheckBox(this).apply {
                setText(R.string.rules_tamper_enable)
                isChecked = store.tamperProtection
                setOnCheckedChangeListener { _, checked ->
                    store.tamperProtection = checked
                    // Applied at once: turning it off is how someone recovers a device.
                    EnforcementService.start(this@DeviceControlActivity)
                }
            },
        )

        column.addView(sectionHeader(getString(R.string.rules_release)))
        column.addView(
            Button(this).apply {
                setText(R.string.rules_release)
                setOnClickListener { confirmRelease(coordinator) }
            },
        )
    }

    private fun confirmRelease(coordinator: EnforcementCoordinator) {
        Background.load(this, { coordinator.isDeviceOwner() }) { owner ->
            if (!owner) {
                Toast.makeText(this, R.string.rules_release_not_owner, Toast.LENGTH_LONG).show()
                return@load
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.rules_release_confirm_title)
                .setMessage(R.string.rules_release_confirm_body)
                .setPositiveButton(R.string.rules_release) { _, _ ->
                    Background.load(this, { coordinator.releaseDevice() }) {
                        Toast.makeText(this, R.string.rules_release_done, Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }
}
