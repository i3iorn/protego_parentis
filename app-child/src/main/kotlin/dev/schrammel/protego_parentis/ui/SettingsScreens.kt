package dev.schrammel.protego_parentis.ui

import android.app.TimePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.audit.Audit
import dev.schrammel.protego_parentis.enforcement.EnforcementCoordinator
import dev.schrammel.protego_parentis.enforcement.EnforcementService
import dev.schrammel.protego_parentis.model.AuditAction
import dev.schrammel.protego_parentis.model.AuditActor
import dev.schrammel.protego_parentis.model.Defaults
import dev.schrammel.protego_parentis.model.InstallPolicy
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.rules.BedtimeSetting
import dev.schrammel.protego_parentis.rules.GoalCoordinator
import dev.schrammel.protego_parentis.rules.LocalPolicyStore
import dev.schrammel.protego_parentis.ui.rules.categories
import dev.schrammel.protego_parentis.ui.rules.policyRuleStore
import dev.schrammel.protego_parentis.usage.InstalledApps
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Only used to turn the install-window length into an offset. */
private const val SECONDS_PER_MINUTE = 60L

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
            val before = store.exemptPackages
            val after = AppPickerActivity.resultFrom(result.data).toSet()
            store.exemptPackages = after
            recordExemptionChanges(before, after)
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
            actionButton().apply {
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

    private fun limitSlider(): LinearLayout {
        // Where the slider stood when the current drag began. The history
        // gets one entry for the whole gesture — recording every step would
        // write forty rows for one adjustment and bury everything else in
        // the log.
        var beganAt = store.dailyLimitMinutes

        return limitSliderBlock(
            initialMinutes = store.dailyLimitMinutes,
            onChanged = { minutes -> store.dailyLimitMinutes = minutes },
            onDragStart = { beganAt = store.dailyLimitMinutes },
            onDragEnd = {
                Audit.record(
                    context = this@LimitsActivity,
                    actor = AuditActor.PARENT,
                    action = AuditAction.DAILY_LIMIT_CHANGED,
                    before = Audit.minutes(beganAt),
                    after = Audit.minutes(store.dailyLimitMinutes),
                )
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

        // Which nights it runs. A school-night bedtime was previously impossible
        // to express: the window ran every night or not at all.
        val nights = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(captionText(getString(R.string.rules_bedtime_nights)))
            addView(
                DayPicker.build(this@LimitsActivity, store.bedtime.days) { chosen ->
                    recordBedtime { it.copy(days = chosen) }
                },
            )
        }

        fun refresh() {
            val setting = store.bedtime
            toggle.isChecked = setting.enabled
            times.visibility = if (setting.enabled) View.VISIBLE else View.GONE
            nights.visibility = if (setting.enabled) View.VISIBLE else View.GONE
            start.text = getString(R.string.rules_bedtime_start, formatTime(setting.start))
            end.text = getString(R.string.rules_bedtime_end, formatTime(setting.end))
        }

        toggle.setOnCheckedChangeListener { _, checked ->
            val before = store.bedtime
            store.bedtime = before.copy(enabled = checked)
            Audit.record(
                context = this@LimitsActivity,
                actor = AuditActor.PARENT,
                action = AuditAction.BEDTIME_ENABLED,
                before = Audit.flag(before.enabled),
                after = Audit.flag(checked),
            )
            refresh()
        }
        start.setOnClickListener {
            pickTime(store.bedtime.start) { picked ->
                recordBedtime { it.copy(start = picked) }
                refresh()
            }
        }
        end.setOnClickListener {
            pickTime(store.bedtime.end) { picked ->
                recordBedtime { it.copy(end = picked) }
                refresh()
            }
        }

        addView(toggle)
        addView(times)
        addView(nights)
        refresh()
    }

    /** Saves a bedtime edit and writes the before-and-after into the history. */
    private fun recordBedtime(edit: (BedtimeSetting) -> BedtimeSetting) {
        val before = store.bedtime
        val after = edit(before)
        store.bedtime = after
        Audit.record(
            context = this,
            actor = AuditActor.PARENT,
            action = AuditAction.BEDTIME_CHANGED,
            before = Audit.timeRange(before.start, before.end),
            after = Audit.timeRange(after.start, after.end),
        )
    }

    /**
     * One entry per app that joined or left the exempt list.
     *
     * A single "exemptions changed" line would be useless: the question anyone
     * brings to the log is which app stopped counting, not that something did.
     */
    private fun recordExemptionChanges(before: Set<String>, after: Set<String>) {
        val apps = InstalledApps(this)
        (before + after)
            .filter { (it in before) != (it in after) }
            .forEach { pkg ->
                Audit.record(
                    context = this,
                    actor = AuditActor.PARENT,
                    action = AuditAction.APP_EXEMPTION_CHANGED,
                    subject = apps.labelFor(PackageName(pkg)),
                    after = Audit.flag(pkg in after),
                )
            }
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
        if (!requireStillInFamily(target)) return
        render()
    }

    private fun render() {
        column.removeAllViews()
        column.addView(screenTitle(getString(R.string.rules_categories_header)))
        column.addView(captionText(getString(R.string.rules_categories_hint)))
        if (!canEdit) column.addView(readOnlyBanner { requestUnlock { onGatedContentVisible() } })

        val categories = policyRuleStore(this, target).categories().sortedBy { it.name.lowercase() }
        if (categories.isEmpty()) {
            column.addView(bodyText(getString(R.string.rules_no_categories)))
        } else {
            column.addView(
                group(
                    categories.map { category ->
                        navigationRow(
                            title = category.name,
                            subtitle = getString(
                                R.string.rules_category_row,
                                formatLimit(category.limitMinutes),
                                resources.getQuantityString(
                                    R.plurals.rules_category_apps,
                                    category.packages.size,
                                    category.packages.size,
                                ),
                            ),
                        ) { startActivity(CategoryEditActivity.intent(this, category.id, target.childId)) }
                    },
                ),
            )
        }

        if (canEdit) {
            column.addView(
                actionButton().apply {
                    setText(R.string.rules_add_category)
                    setOnClickListener {
                        startActivity(CategoryEditActivity.intent(this@CategoriesActivity, childId = target.childId))
                    }
                },
            )
        }
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
        var chosenMinutes = startingMinutes
        val slider = limitSliderBlock(
            initialMinutes = startingMinutes,
            onChanged = { minutes -> chosenMinutes = minutes },
        )
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
                        goals.saveGoal(false, chosenMinutes, prize.text.toString())
                        Audit.record(
                            context = this@GoalActivity,
                            actor = AuditActor.PARENT,
                            action = AuditAction.GOAL_CHANGED,
                            after = Audit.minutes(0),
                        )
                    }
                }
            },
        )
        settings.addView(slider)
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
                    val minutes = chosenMinutes
                    val prizeChanged = text != existing.prize
                    goals.saveGoal(true, minutes, text)
                    Audit.record(
                        context = this@GoalActivity,
                        actor = AuditActor.PARENT,
                        action = AuditAction.GOAL_CHANGED,
                        before = Audit.minutes(startingMinutes),
                        after = Audit.minutes(minutes),
                    )
                    // The prize text itself is never recorded. It is meant to be a
                    // surprise, and a history that spoils it would not be opened twice.
                    if (prizeChanged) {
                        Audit.record(
                            context = this@GoalActivity,
                            actor = AuditActor.PARENT,
                            action = AuditAction.GOAL_PRIZE_CHANGED,
                        )
                    }
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

    /**
     * Five controls that used to float.
     *
     * Every other list in this app sits on a raised surface; this screen had bare
     * checkboxes and buttons straight on the window, which is the look the rest of
     * the design was written to get away from. Each setting now gets a card, with
     * the sentence explaining it inside the same card rather than orphaned above.
     *
     * Two headings also went. "Lock this phone down" was the section heading *and*
     * the checkbox label directly beneath it, and so was "Release this phone" — a
     * screen that says a thing twice in a row reads as a rendering fault.
     */
    private fun render() {
        val store = LocalPolicyStore(this)
        val coordinator = EnforcementCoordinator(this)

        column.removeAllViews()
        column.addView(screenTitle(getString(R.string.rules_device_header)))

        column.addView(sectionHeader(getString(R.string.rules_security_header)))
        column.addView(
            card {
                addView(captionText(getString(R.string.parent_settings_pin_body)))
                addView(
                    actionButton().apply {
                        setText(R.string.rules_change_pin)
                        setOnClickListener {
                            // The old PIN is verified inside, and replaced only
                            // once the new one is confirmed.
                            startActivity(
                                PinActivity.intent(this@DeviceControlActivity, change = true),
                            )
                        }
                    },
                )
            },
        )
        column.addView(
            card {
                addView(
                    TextView(this@DeviceControlActivity).apply {
                        setText(R.string.biometric_header)
                        styleAs(R.style.TextAppearance_Dementor_Subtitle)
                    },
                )
                addView(biometricSetting { render() })
            },
        )

        column.addView(sectionHeader(getString(R.string.rules_protection_header)))
        column.addView(
            card {
                addView(
                    CheckBox(this@DeviceControlActivity).apply {
                        setText(R.string.rules_tamper_enable)
                        isChecked = store.tamperProtection
                        setOnCheckedChangeListener { _, checked ->
                            val before = store.tamperProtection
                            store.tamperProtection = checked
                            Audit.record(
                                context = this@DeviceControlActivity,
                                actor = AuditActor.PARENT,
                                action = AuditAction.TAMPER_PROTECTION_CHANGED,
                                before = Audit.flag(before),
                                after = Audit.flag(checked),
                            )
                            // Applied at once: turning it off is how someone
                            // recovers a device.
                            EnforcementService.start(this@DeviceControlActivity)
                        }
                    },
                )
                addView(captionText(getString(R.string.rules_tamper_hint)))
            },
        )
        column.addView(
            card {
                addView(
                    CheckBox(this@DeviceControlActivity).apply {
                        setText(R.string.rules_installs_gate)
                        isChecked = store.installPolicy == InstallPolicy.GATED
                        setOnCheckedChangeListener { _, gated ->
                            val chosen = if (gated) InstallPolicy.GATED else InstallPolicy.NOTIFY
                            store.installPolicy = chosen
                            // Switching the gate off leaves no window behind, so
                            // switching it back on is shut rather than
                            // mysteriously open.
                            if (!gated) store.installWindowUntil = null
                            Audit.record(
                                context = this@DeviceControlActivity,
                                actor = AuditActor.PARENT,
                                action = AuditAction.INSTALL_POLICY_CHANGED,
                                after = chosen.name,
                            )
                            EnforcementService.start(this@DeviceControlActivity)
                            render()
                        }
                    },
                )
                addView(captionText(getString(R.string.rules_installs_hint)))
                if (store.installPolicy == InstallPolicy.GATED) addView(installWindow(store))
            },
        )

        column.addView(sectionHeader(getString(R.string.rules_release_header)))
        column.addView(
            card {
                addView(captionText(getString(R.string.rules_release_body)))
                addView(
                    actionButton().apply {
                        setText(R.string.rules_release)
                        setOnClickListener { confirmRelease(coordinator) }
                    },
                )
            },
        )
    }

    /**
     * Opening the gate for a while.
     *
     * Without this, gating installs locks the door from the outside: nobody can
     * add an app again, including the parent who turned it on. A window that
     * expires on its own means forgetting to close it is not the same as never
     * having gated installs at all.
     */
    private fun installWindow(store: LocalPolicyStore): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            val until = store.installWindowUntil
            val open = until != null && Instant.now().isBefore(until)
            addView(
                captionText(
                    if (open) {
                        getString(
                            R.string.rules_installs_window_open,
                            formatTime(
                                LocalTime.ofInstant(checkNotNull(until), ZoneId.systemDefault()),
                            ),
                        )
                    } else {
                        getString(R.string.rules_installs_window_shut)
                    },
                ),
            )
            addView(
                Button(context).apply {
                    text = resources.getQuantityString(
                        R.plurals.rules_installs_open,
                        Defaults.INSTALL_WINDOW_MINUTES.toInt(),
                        Defaults.INSTALL_WINDOW_MINUTES.toInt(),
                    )
                    setOnClickListener {
                        store.installWindowUntil = Instant.now()
                            .plusSeconds(Defaults.INSTALL_WINDOW_MINUTES * SECONDS_PER_MINUTE)
                        Audit.record(
                            context = this@DeviceControlActivity,
                            actor = AuditActor.PARENT,
                            action = AuditAction.INSTALL_WINDOW_OPENED,
                            after = Audit.minutes(Defaults.INSTALL_WINDOW_MINUTES.toInt()),
                        )
                        EnforcementService.start(this@DeviceControlActivity)
                        render()
                    }
                },
            )
        }

    private fun confirmRelease(coordinator: EnforcementCoordinator) {
        Background.load(this, { coordinator.isDeviceOwner() }) { owner ->
            if (!owner) {
                Toast.makeText(this, R.string.rules_release_not_owner, Toast.LENGTH_LONG).show()
                return@load
            }
            confirmDialog(
                R.string.rules_release_confirm_title,
                R.string.rules_release_confirm_body,
                R.string.rules_release,
            ) {
                Audit.record(
                    context = this,
                    actor = AuditActor.PARENT,
                    action = AuditAction.DEVICE_RELEASED,
                )
                Background.load(this, { coordinator.releaseDevice() }) {
                    Toast.makeText(this, R.string.rules_release_done, Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }
}
