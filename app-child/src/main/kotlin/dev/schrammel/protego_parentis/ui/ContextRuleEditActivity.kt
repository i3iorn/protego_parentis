package dev.schrammel.protego_parentis.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.audit.Audit
import dev.schrammel.protego_parentis.model.AuditAction
import dev.schrammel.protego_parentis.model.AuditActor
import dev.schrammel.protego_parentis.model.CategoryId
import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.model.DeviceTag
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.PlaceRef
import dev.schrammel.protego_parentis.model.RecurringWindow
import dev.schrammel.protego_parentis.model.RuleAction
import dev.schrammel.protego_parentis.model.RuleEffect
import dev.schrammel.protego_parentis.model.RuleTarget
import dev.schrammel.protego_parentis.model.WeeklyBudget
import dev.schrammel.protego_parentis.policy.ConditionDraft
import dev.schrammel.protego_parentis.policy.ContextRuleAuthoring
import dev.schrammel.protego_parentis.policy.ContextRuleFormat
import dev.schrammel.protego_parentis.policy.GroupMode
import dev.schrammel.protego_parentis.policy.add
import dev.schrammel.protego_parentis.policy.groupDepthAt
import dev.schrammel.protego_parentis.policy.hasImpossibleAtLeast
import dev.schrammel.protego_parentis.policy.nodeAt
import dev.schrammel.protego_parentis.policy.remove
import dev.schrammel.protego_parentis.policy.setMode
import dev.schrammel.protego_parentis.policy.setNegated
import dev.schrammel.protego_parentis.policy.toCondition
import dev.schrammel.protego_parentis.policy.update
import dev.schrammel.protego_parentis.rules.MigratedRuleIds
import dev.schrammel.protego_parentis.sync.RosterStore
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.ui.rules.RuleScope
import dev.schrammel.protego_parentis.ui.rules.RuleTargets
import dev.schrammel.protego_parentis.ui.rules.categories
import dev.schrammel.protego_parentis.ui.rules.contextRuleRuleStore
import dev.schrammel.protego_parentis.ui.rules.delete
import dev.schrammel.protego_parentis.ui.rules.find
import dev.schrammel.protego_parentis.ui.rules.placeRuleStore
import dev.schrammel.protego_parentis.ui.rules.policyRuleStore
import dev.schrammel.protego_parentis.ui.rules.upsert
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

/**
 * Builds one rule, read as a sentence: *when this, do that, to those* — on
 * whichever kind of phone edits [target]'s rules (#43).
 *
 * The three parts are chosen separately rather than offered as a list of preset
 * rules, because the useful combinations are family-specific. "Block games on the
 * school Wi-Fi" and "let homework run freely at Grandma's" are the same shape.
 *
 * "When" is a tree: rows and nested groups, each group combining its rows as
 * all, any or at least N, and any row or group negatable ([ConditionDraft]).
 * An optional "except when" clause switches the rule off while it holds, and a
 * rule can have several "do this, to that" actions.
 */
class ContextRuleEditActivity : ParentGatedActivity() {

    private lateinit var target: RuleScope
    private lateinit var container: LinearLayout

    private var ruleId: String = ""
    private var name: String = ""
    private var enabled: Boolean = true

    /** "When", as the editable tree the screen draws. Its root's NOT is the "invert" checkbox. */
    private var whenDraft = ConditionDraft.Group()

    /** "Except when", or null while the rule has no exception. */
    private var exceptDraft: ConditionDraft.Group? = null

    private var actions: MutableList<RuleAction> = mutableListOf(DEFAULT_ACTION)

    /** Which of [actions] the app picker is choosing a target for. */
    private var pickingForAction: Int = 0

    /**
     * Set just before [pickApps] launches for "everything except some apps," so
     * its callback knows to build a [RuleTarget.EverythingExcept] instead of
     * merging into [RuleTarget.Apps]/[RuleTarget.Mixed] via [mergedTarget] —
     * the two pickers share one activity but produce different target shapes.
     */
    private var pickingExcept: Boolean = false

    private val pickApps = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val picked = AppPickerActivity.resultFrom(result.data)
        val index = pickingForAction
        val current = actions.getOrNull(index) ?: return@registerForActivityResult
        if (pickingExcept) {
            actions[index] = current.copy(target = RuleTarget.EverythingExcept(picked.map(::PackageName).toSet()))
        } else if (picked.isNotEmpty()) {
            actions[index] = current.copy(
                target = mergedTarget(picked.map(::PackageName).toSet(), current.target.targetCategories()),
            )
        }
        render()
    }

    private val describer by lazy {
        RuleDescriber(
            activity = this,
            placeName = { id -> places().firstOrNull { it.id == id }?.name },
            categoryName = { id -> policyRuleStore(this, target).categories().firstOrNull { it.id == id.value }?.name },
        )
    }

    override fun supportsReadOnly() = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val resolved = RuleTargets.resolve(this) ?: return finish()
        target = resolved

        val existing = intent.getStringExtra(EXTRA_ID)
            ?.let { id -> contextRuleRuleStore(this, target).find(id) }
        ruleId = existing?.id ?: UUID.randomUUID().toString()
        name = existing?.name.orEmpty()
        enabled = existing?.enabled ?: true
        actions = existing?.actions?.toMutableList() ?: mutableListOf(DEFAULT_ACTION)
        whenDraft = ConditionDraft.fromCondition(existing?.condition ?: Condition.Always)
        exceptDraft = existing?.exceptWhen?.let(ConditionDraft::fromCondition)

        container = contentColumn()
        showColumn(container)
    }

    override fun onGatedContentVisible() {
        if (!requireStillInFamily(target)) return
        render()
    }

    private fun render() {
        container.removeAllViews()
        container.addView(heading(getString(R.string.context_edit_title), R.style.TextAppearance_Dementor_Title))
        container.addView(
            actionButton().apply {
                setText(R.string.context_tutorial_open)
                setOnClickListener { startActivity(RulesTutorialActivity.intent(this@ContextRuleEditActivity)) }
            },
        )
        if (!canEdit) container.addView(readOnlyBanner { requestUnlock { onGatedContentVisible() } })

        container.addView(
            EditText(this).apply {
                hint = getString(R.string.context_name_hint)
                setText(name)
                setSingleLine()
                isEnabled = canEdit
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                addTextChangedListener(SimpleWatcher { name = it })
            },
        )
        container.addView(
            CheckBox(this).apply {
                setText(R.string.context_enabled)
                isChecked = enabled
                isEnabled = canEdit
                setOnCheckedChangeListener { _, checked -> enabled = checked }
            },
        )

        // ---- when ----
        container.addView(
            headingWithInfo(
                heading(getString(R.string.context_when_header), R.style.TextAppearance_Dementor_Section),
                R.string.context_when_info_title,
                R.string.context_when_info_body,
            ),
        )
        renderGroup(container, whenSlot, emptyList())
        // Under the whole tree rather than the first row: this negates the
        // root group, so everything above it.
        container.addView(
            CheckBox(this).apply {
                setText(R.string.context_invert)
                isChecked = whenDraft.negated
                isEnabled = canEdit
                setOnCheckedChangeListener { _, checked ->
                    whenDraft = whenDraft.setNegated(emptyList(), checked)
                    render()
                }
            },
        )

        // ---- except when ----
        container.addView(
            headingWithInfo(
                heading(getString(R.string.context_except_header), R.style.TextAppearance_Dementor_Section),
                R.string.context_except_info_title,
                R.string.context_except_info_body,
            ),
        )
        if (exceptDraft == null) {
            container.addView(
                choice(getString(R.string.context_add_exception)) {
                    chooseCondition(null) { picked ->
                        exceptDraft = ConditionDraft.Group(children = listOf(ConditionDraft.Leaf(picked)))
                        render()
                    }
                },
            )
        } else {
            renderGroup(container, exceptSlot, emptyList())
            container.addView(choice(getString(R.string.context_remove_exception)) { exceptDraft = null; render() })
        }

        // ---- do what, to whom ----
        container.addView(
            headingWithInfo(
                heading(getString(R.string.context_do_header), R.style.TextAppearance_Dementor_Section),
                R.string.context_do_info_title,
                R.string.context_do_info_body,
            ),
        )
        actions.forEachIndexed { index, action -> container.addView(actionRow(index, action)) }
        if (actions.size < ContextRuleFormat.MAX_ACTIONS) {
            container.addView(
                choice(getString(R.string.context_add_action)) {
                    actions.add(DEFAULT_ACTION)
                    render()
                },
            )
        }

        if (canEdit) {
            container.addView(
                Button(this).apply {
                    setText(R.string.context_save)
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                        .apply { topMargin = dimen(R.dimen.space_xl) }
                    setOnClickListener { save() }
                },
            )
            if (intent.hasExtra(EXTRA_ID)) {
                container.addView(
                    Button(this).apply {
                        setText(R.string.context_delete)
                        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                            .apply { topMargin = dimen(R.dimen.space_sm) }
                        setOnClickListener {
                            confirmDialog(
                                name.ifBlank { getString(R.string.context_delete) },
                                getString(R.string.context_delete_confirm),
                                R.string.context_delete,
                            ) { delete() }
                        }
                    },
                )
            }
        }
    }

    private fun delete() {
        val deleted = name
        val store = contextRuleRuleStore(this, target)
        store.delete(ruleId)
        Audit.record(
            context = this,
            actor = AuditActor.PARENT,
            action = AuditAction.CONTEXT_RULE_DELETED,
            subject = deleted,
        )
        acknowledgeRuleWrite(store, R.string.context_delete_sent_remote)
        finish()
    }

    // ---------- condition tree ----------

    /** Where a tree being drawn lives, so a row can read and replace the whole tree it belongs to. */
    private class DraftSlot(val get: () -> ConditionDraft.Group?, val set: (ConditionDraft.Group) -> Unit)

    private val whenSlot = DraftSlot({ whenDraft }, { whenDraft = it })
    private val exceptSlot = DraftSlot({ exceptDraft }, { exceptDraft = it })

    private fun DraftSlot.edit(change: (ConditionDraft.Group) -> ConditionDraft.Group) {
        get()?.let { set(change(it)) }
        render()
    }

    /**
     * Draws the group at [path] and everything under it. A nested group gets
     * its own header row (how it combines, NOT, remove) and is indented, so
     * which rows belong to which group is visible at a glance.
     */
    private fun renderGroup(into: LinearLayout, slot: DraftSlot, path: List<Int>) {
        val tree = slot.get() ?: return
        val group = tree.nodeAt(path) as? ConditionDraft.Group ?: return
        val isRoot = path.isEmpty()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            if (!isRoot) setPadding(dimen(R.dimen.space_lg), 0, 0, 0)
        }

        if (!isRoot) {
            box.addView(
                horizontalRow(
                    choice(modeLabel(group)) { chooseMode(slot, path) },
                    notToggle(group.negated) { checked -> slot.edit { it.setNegated(path, checked) } },
                    removeButton(R.string.context_remove_group) { slot.edit { it.remove(path) } },
                ),
            )
        } else if (group.children.size > 1) {
            box.addView(choice(modeLabel(group)) { chooseMode(slot, path) })
        }

        group.children.forEachIndexed { index, child ->
            val childPath = path + index
            when (child) {
                is ConditionDraft.Leaf -> box.addView(
                    horizontalRow(
                        choice(describer.condition(child.condition)) {
                            chooseCondition(child.condition) { picked ->
                                slot.edit { current ->
                                    current.update(childPath) { (it as ConditionDraft.Leaf).copy(condition = picked) }
                                }
                            }
                        },
                        notToggle(child.negated) { checked -> slot.edit { it.setNegated(childPath, checked) } },
                        removeButton(R.string.context_remove_condition) { slot.edit { it.remove(childPath) } },
                    ),
                )

                is ConditionDraft.Group -> renderGroup(box, slot, childPath)
            }
        }

        box.addView(
            choice(getString(R.string.context_add_condition)) {
                chooseCondition(null) { picked -> slot.edit { it.add(path, ConditionDraft.Leaf(picked)) } }
            },
        )
        if (tree.groupDepthAt(path) < ConditionDraft.MAX_GROUP_DEPTH) {
            box.addView(
                choice(getString(R.string.context_add_group)) {
                    chooseCondition(null) { picked ->
                        slot.edit { it.add(path, ConditionDraft.Group(children = listOf(ConditionDraft.Leaf(picked)))) }
                    }
                },
            )
        }
        into.addView(box)
    }

    private fun modeLabel(group: ConditionDraft.Group): String = when (val mode = group.mode) {
        GroupMode.All -> getString(R.string.context_combine_all)
        GroupMode.Any -> getString(R.string.context_combine_any)
        is GroupMode.AtLeast -> getString(R.string.context_combine_at_least, mode.count)
    }

    /**
     * All, any, or "at least N" for every N strictly between them: at least 1
     * is any and at least all of them is all, and are offered as those.
     */
    private fun chooseMode(slot: DraftSlot, path: List<Int>) {
        if (!canEdit) return
        val group = slot.get()?.nodeAt(path) as? ConditionDraft.Group ?: return
        val options = buildList {
            add(getString(R.string.context_combine_all) to { slot.edit { it.setMode(path, GroupMode.All) } })
            add(getString(R.string.context_combine_any) to { slot.edit { it.setMode(path, GroupMode.Any) } })
            (2 until group.children.size).forEach { count ->
                add(
                    getString(R.string.context_combine_at_least, count) to {
                        slot.edit { it.setMode(path, GroupMode.AtLeast(count)) }
                    },
                )
            }
        }
        menu(R.string.context_combine_header, options)
    }

    private fun notToggle(checked: Boolean, onChange: (Boolean) -> Unit) = CheckBox(this).apply {
        setText(R.string.context_row_not)
        isChecked = checked
        isEnabled = canEdit
        setOnCheckedChangeListener { _, value -> onChange(value) }
    }

    private fun removeButton(textRes: Int, onClick: () -> Unit) = Button(this).apply {
        setText(textRes)
        isEnabled = canEdit
        setOnClickListener { onClick() }
    }

    /** [main] takes the spare width; everything after it keeps its own size. */
    private fun horizontalRow(main: View, vararg rest: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        addView(main.apply { layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f) })
        rest.forEach { addView(it) }
    }

    // ---------- actions ----------

    /** One "do this, to that" pair. Only the first carries the "To" info button, so it isn't repeated. */
    private fun actionRow(index: Int, action: RuleAction) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        if (index > 0) setPadding(0, dimen(R.dimen.space_md), 0, 0)
        addView(choice(describer.effect(action.effect)) { chooseEffect(index) })
        val toHeading = heading(getString(R.string.context_to_header), R.style.TextAppearance_Dementor_Caption)
        addView(
            if (index == 0) {
                headingWithInfo(toHeading, R.string.context_to_info_title, R.string.context_to_info_body)
            } else {
                toHeading
            },
        )
        addView(choice(describer.target(action.target)) { chooseTarget(index) })
        if (actions.size > 1) {
            addView(removeButton(R.string.context_remove_action) { actions.removeAt(index); render() })
        }
    }

    private fun places(): List<PlaceRef> = placeRuleStore(this, target).read()

    // ---------- choosing ----------

    /**
     * Picks one condition, prefilling from [current] when replacing an
     * existing one — used both for the primary "when" row and, since #87, for
     * adding or editing an extra one. [current] is null when adding a new one,
     * since there is nothing yet to prefill from.
     */
    private fun chooseCondition(current: Condition?, onPicked: (Condition) -> Unit) {
        if (!canEdit) return
        val places = places()
        val options = buildList {
            add(getString(R.string.context_when_always) to { onPicked(Condition.Always) })
            add(getString(R.string.context_choose_wifi) to { askForWifi(current, onPicked) })
            places.forEach { place ->
                add(getString(R.string.context_when_place, place.name) to { onPicked(Condition.AtPlace(place.id)) })
            }
            add(getString(R.string.context_choose_device) to { chooseDevice(onPicked) })
            add(getString(R.string.context_choose_battery) to { askForBattery(current, onPicked) })
            add(getString(R.string.context_choose_window) to { askForWindow(current, onPicked) })
            add(getString(R.string.context_choose_allowance) to { askForAllowance(current, onPicked) })
        }
        menu(R.string.context_when_header, options)
    }

    /** A percentage rather than free text — there is no wrong value to type. */
    private fun askForBattery(current: Condition?, onPicked: (Condition) -> Unit) {
        val existing = (current as? Condition.BatteryBelow)?.percent ?: DEFAULT_BATTERY_PERCENT
        sliderDialog(
            title = R.string.context_choose_battery,
            max = MAX_BATTERY_PERCENT - 1,
            initialProgress = existing - 1,
            format = { getString(R.string.context_when_battery, it + 1) },
        ) { progress -> onPicked(Condition.BatteryBelow(progress + 1)) }
    }

    /**
     * A recurring window — days plus a start/end time, the shape the old
     * dedicated schedules screen used to edit before #106 folded it in here.
     * Built here too rather than only ever produced by the schedule migration
     * (#105), so a rule with this condition round-trips through its own
     * editor instead of losing the window the moment somebody renames the
     * rule and saves.
     */
    private fun askForWindow(current: Condition?, onPicked: (Condition) -> Unit) {
        val existing = current as? Condition.DuringWindow
        var days = existing?.window?.days ?: DEFAULT_WINDOW_DAYS
        var start = existing?.window?.start ?: DEFAULT_WINDOW_START
        var end = existing?.window?.end ?: DEFAULT_WINDOW_END
        var everyOtherWeek = existing?.window?.parityAnchor != null
        var parityAnchorEpochDay = existing?.window?.parityAnchor?.toEpochDay()

        lateinit var dialog: AlertDialog
        lateinit var body: LinearLayout

        fun renderBody() {
            body.removeAllViews()
            body.addView(TextView(this).apply { setText(R.string.context_window_days_header) })
            body.addView(DayPicker.build(this@ContextRuleEditActivity, days) { days = it })
            body.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(
                        Button(context).apply {
                            text = getString(R.string.schedules_from, formatTime(start))
                            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                            setOnClickListener { pickTime(start) { start = it; renderBody() } }
                        },
                    )
                    addView(
                        Button(context).apply {
                            text = getString(R.string.schedules_to, formatTime(end))
                            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                            setOnClickListener { pickTime(end) { end = it; renderBody() } }
                        },
                    )
                },
            )
            body.addView(
                CheckBox(this).apply {
                    setText(R.string.context_window_every_other_week)
                    isChecked = everyOtherWeek
                    setOnCheckedChangeListener { _, checked ->
                        everyOtherWeek = checked
                        if (checked && parityAnchorEpochDay == null) {
                            parityAnchorEpochDay = LocalDate.now().toEpochDay()
                        }
                    }
                },
            )
        }

        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dimen(R.dimen.screen_padding)
            setPadding(pad, pad, pad, 0)
        }
        renderBody()

        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.context_choose_window)
            .setView(body)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        // Wired after showing, like the old dedicated schedule editor's own
        // window dialog did — a plain setPositiveButton closure dismisses on
        // any input, valid or not, and this validates the same way it did.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (days.isEmpty()) {
                    toast(getString(R.string.schedules_needs_days))
                    return@setOnClickListener
                }
                if (start == end) {
                    toast(getString(R.string.schedules_needs_window))
                    return@setOnClickListener
                }
                onPicked(
                    Condition.DuringWindow(
                        RecurringWindow(
                            days = days,
                            start = start,
                            end = end,
                            parityAnchor = if (everyOtherWeek) {
                                parityAnchorEpochDay?.let(LocalDate::ofEpochDay)
                            } else {
                                null
                            },
                        ),
                    ),
                )
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun pickTime(current: LocalTime, onPicked: (LocalTime) -> Unit) {
        android.app.TimePickerDialog(
            this,
            { _, hour, minute -> onPicked(LocalTime.of(hour, minute)) },
            current.hour,
            current.minute,
            true,
        ).show()
    }

    /** How much of today's allowance must already be spent for this to hold. */
    private fun askForAllowance(current: Condition?, onPicked: (Condition) -> Unit) {
        val existing = (current as? Condition.AllowanceSpent)?.minutes ?: DEFAULT_ALLOWANCE_MINUTES
        sliderDialog(
            title = R.string.context_choose_allowance,
            max = LimitSlider.MAX_STEPS,
            initialProgress = LimitSlider.progressFor(existing),
            format = { formatLimit(LimitSlider.minutesFor(it)) },
        ) { progress -> onPicked(Condition.AllowanceSpent(LimitSlider.minutesFor(progress))) }
    }

    /**
     * Offers the network the phone is on right now, since that is almost always
     * the one being written a rule about, but allows typing another.
     */
    @SuppressLint("MissingPermission")
    private fun askForWifi(current: Condition?, onPicked: (Condition) -> Unit) {
        requestLocationIfNeeded()
        val currentSsid = dev.schrammel.protego_parentis.context.ContextProvider(this)
            .current(emptyList()).wifiSsid
        val prefill = (current as? Condition.OnWifi)?.ssid ?: currentSsid.orEmpty()

        promptForText(
            R.string.context_choose_wifi,
            R.string.context_wifi_hint,
            prefill = prefill,
        ) { ssid -> onPicked(Condition.OnWifi(ssid)) }
    }

    /**
     * Offers this phone's paired devices to pick from (#289), falling back to
     * typing an address. Only when editing this phone's own rules: on a
     * parent's phone editing a child's, the devices paired *here* say nothing
     * about the child's phone.
     */
    private fun chooseDevice(onPicked: (Condition) -> Unit) {
        requestBluetoothIfNeeded()
        val paired = if (target.isOwnDevice && hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            pairedDevices()
        } else {
            emptyList()
        }
        if (paired.isEmpty()) {
            enterDeviceAddress(onPicked)
            return
        }
        menu(
            R.string.context_choose_device,
            paired.map { (name, address) ->
                "$name ($address)" to { onPicked(Condition.NearDevice(DeviceTag(address))) }
            } + (getString(R.string.context_device_enter_address) to { enterDeviceAddress(onPicked) }),
        )
    }

    private fun enterDeviceAddress(onPicked: (Condition) -> Unit) {
        promptForText(
            R.string.context_choose_device,
            R.string.context_device_hint,
            message = getString(R.string.context_device_explainer),
        ) { tag -> onPicked(Condition.NearDevice(DeviceTag(tag.trim().uppercase()))) }
    }

    @SuppressLint("MissingPermission")
    private fun pairedDevices(): List<Pair<String, String>> = runCatching {
        getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
            .map { (it.name ?: it.address) to it.address }
            .sortedBy { it.first.lowercase() }
    }.getOrDefault(emptyList())

    private fun setEffect(index: Int, effect: RuleEffect) {
        actions.getOrNull(index)?.let { actions[index] = it.copy(effect = effect) }
        render()
    }

    private fun setTarget(index: Int, ruleTarget: RuleTarget) {
        actions.getOrNull(index)?.let { actions[index] = it.copy(target = ruleTarget) }
        render()
    }

    private fun chooseEffect(index: Int) {
        if (!canEdit) return
        menu(
            R.string.context_do_header,
            listOf(
                getString(R.string.context_effect_block) to { setEffect(index, RuleEffect.Block()) },
                getString(R.string.context_effect_allow) to { setEffect(index, RuleEffect.Allow) },
                getString(R.string.context_effect_always_block) to { setEffect(index, RuleEffect.AlwaysBlock()) },
                getString(R.string.context_choose_safety_exempt) to { setEffect(index, RuleEffect.SafetyExempt) },
                getString(R.string.context_choose_budget) to { askForBudget(index) },
            ),
        )
    }

    private fun askForBudget(index: Int) {
        val existingMinutes = (actions.getOrNull(index)?.effect as? RuleEffect.SetBudget)
            ?.budget?.default?.inWholeMinutes?.toInt()
        sliderDialog(
            title = R.string.context_choose_budget,
            max = LimitSlider.MAX_STEPS,
            initialProgress = LimitSlider.progressFor(existingMinutes ?: DEFAULT_BUDGET_MINUTES),
            format = { formatLimit(LimitSlider.minutesFor(it)) },
        ) { progress ->
            setEffect(index, RuleEffect.SetBudget(WeeklyBudget.flat(LimitSlider.minutesFor(progress).minutes)))
        }
    }

    private fun chooseTarget(index: Int) {
        if (!canEdit) return
        val current = actions.getOrNull(index)?.target ?: return
        menu(
            R.string.context_to_header,
            listOf(
                getString(R.string.context_target_everything) to { setTarget(index, RuleTarget.Everything) },
                getString(R.string.context_choose_categories) to { chooseCategories(index) },
                getString(R.string.context_choose_apps) to {
                    pickingExcept = false
                    pickingForAction = index
                    pickApps.launch(
                        AppPickerActivity.intent(
                            this,
                            getString(R.string.context_choose_apps),
                            current.targetApps().map { it.value },
                            target.childId,
                        ),
                    )
                },
                getString(R.string.context_choose_everything_except) to {
                    pickingExcept = true
                    pickingForAction = index
                    val except = (current as? RuleTarget.EverythingExcept)?.packages.orEmpty()
                    pickApps.launch(
                        AppPickerActivity.intent(
                            this,
                            getString(R.string.context_choose_everything_except),
                            except.map { it.value },
                            target.childId,
                        ),
                    )
                },
            ),
        )
    }

    private fun chooseCategories(index: Int) {
        val current = actions.getOrNull(index)?.target ?: return
        val categories = policyRuleStore(this, target).categories()
        if (categories.isEmpty()) {
            toast(getString(R.string.rules_no_categories))
            return
        }
        val selected = current.targetCategories().map { it.value }
        val checked = categories.map { it.id in selected }.toBooleanArray()
        val chosen = selected.toMutableSet()

        AlertDialog.Builder(this)
            .setTitle(R.string.context_choose_categories)
            .setMultiChoiceItems(
                categories.map { it.name }.toTypedArray(),
                checked,
            ) { _, position, isChecked ->
                val id = categories[position].id
                if (isChecked) chosen += id else chosen -= id
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                setTarget(index, mergedTarget(current.targetApps(), chosen.map(::CategoryId).toSet()))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * The one package [ruleId] was derived from, if it is one of
     * [dev.schrammel.protego_parentis.rules.LocalPolicyStore]'s well-known
     * per-app ids (#223) — the slot [AppLimitsActivity]/[AppControlActivity]
     * each read their own rules by. The id format has no room for a second
     * package: `appBudgetLimits` (see `RuleStores.kt`) only recognizes such a
     * rule while its target is still exactly this one app, so widening it
     * here would not grow what the rule covers from those screens' point of
     * view — it would just make the rule vanish from the one that created it,
     * while it goes on enforcing regardless. Null for an ordinary rule, which
     * has no such shape to protect.
     */
    private fun wellKnownSingleAppTarget(): RuleTarget.Apps? =
        MigratedRuleIds.singleAppOf(ruleId)?.let { RuleTarget.Apps(setOf(it)) }

    private fun save() {
        if (name.isBlank()) {
            toast(getString(R.string.context_name_required))
            return
        }
        wellKnownSingleAppTarget()?.let { locked ->
            if (actions.size != 1 || actions.single().target != locked) {
                toast(getString(R.string.context_well_known_target_locked))
                return
            }
        }
        if (whenDraft.hasImpossibleAtLeast() || exceptDraft?.hasImpossibleAtLeast() == true) {
            toast(getString(R.string.context_at_least_impossible))
            return
        }
        val rule = ContextRule(
            id = ruleId,
            name = name.trim(),
            enabled = enabled,
            condition = whenDraft.toCondition(),
            actions = actions.toList(),
            exceptWhen = exceptDraft?.toCondition(),
        )
        confirmOlderPhones(rule) { confirmBlackout(rule) { commit(rule) } }
    }

    /**
     * Warns before saving a V2 rule (see [ContextRuleFormat]) while any phone
     * in the family has not said it runs a build that reads one: an older
     * build ignores the rule, and can delete it when a parent edits rules on
     * it.
     */
    private fun confirmOlderPhones(rule: ContextRule, then: () -> Unit) {
        val required = ContextRuleFormat.requiredVersion(rule) ?: return then()
        val older = RosterStore(this).devices.filterNot { ContextRuleFormat.supports(it.appVersion, required) }
        if (older.isEmpty()) return then()
        confirmDialog(
            R.string.context_older_phones_title,
            getString(
                R.string.context_older_phones_body,
                older.joinToString(", ") { it.name },
                required,
            ),
            R.string.context_save,
            then,
        )
    }

    private fun confirmBlackout(rule: ContextRule, then: () -> Unit) {
        if (!ContextRuleAuthoring.looksLikeTotalBlackout(rule, contextRuleRuleStore(this, target).read())) return then()
        val alwaysBlocksEverything = rule.actions.any {
            it.effect is RuleEffect.AlwaysBlock && it.target == RuleTarget.Everything
        }
        val bodyRes = if (alwaysBlocksEverything) {
            R.string.context_blackout_body_always_block
        } else {
            R.string.context_blackout_body
        }
        confirmDialog(R.string.context_blackout_title, bodyRes, R.string.context_save, then)
    }

    private fun commit(rule: ContextRule) {
        contextRuleRuleStore(this, target).upsert(rule)
        Audit.record(
            context = this,
            actor = AuditActor.PARENT,
            action = AuditAction.CONTEXT_RULE_SAVED,
            subject = name.trim(),
        )
        // A local save takes effect the instant this returns — the enforcement
        // service already read the same store. A remote one only reaches the
        // child's own store once sync carries it there, which without this
        // would look identical from here: same button, same instant finish().
        acknowledgeRuleWrite(contextRuleRuleStore(this, target))
        finish()
    }

    // ---------- permissions ----------

    /**
     * Asked for only when a rule actually needs it, rather than at first launch.
     * A permission prompt with no visible reason is one people refuse.
     */
    private fun requestLocationIfNeeded() {
        if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            REQUEST_LOCATION,
        )
    }

    private fun requestBluetoothIfNeeded() {
        if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
            REQUEST_BLUETOOTH,
        )
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    // ---------- targets ----------

    /** [RuleTarget]'s apps, whichever variant is carrying them — see [mergedTarget]. */
    private fun RuleTarget.targetApps(): Set<PackageName> = when (this) {
        is RuleTarget.Apps -> packages
        is RuleTarget.Mixed -> packages
        else -> emptySet()
    }

    /** [RuleTarget]'s categories, whichever variant is carrying them — see [mergedTarget]. */
    private fun RuleTarget.targetCategories(): Set<CategoryId> = when (this) {
        is RuleTarget.Categories -> categories
        is RuleTarget.Mixed -> categories
        else -> emptySet()
    }

    /**
     * The simplest [RuleTarget] that carries [apps] and [categories] (#90) —
     * [RuleTarget.Mixed] only when a rule genuinely has both, so choosing just
     * one kind still round-trips through the plain [RuleTarget.Apps] or
     * [RuleTarget.Categories] it always did.
     */
    private fun mergedTarget(apps: Set<PackageName>, categories: Set<CategoryId>): RuleTarget = when {
        apps.isEmpty() && categories.isEmpty() -> RuleTarget.Everything
        categories.isEmpty() -> RuleTarget.Apps(apps)
        apps.isEmpty() -> RuleTarget.Categories(categories)
        else -> RuleTarget.Mixed(apps, categories)
    }

    // ---------- view helpers ----------

    private fun menu(titleRes: Int, options: List<Pair<String, () -> Unit>>) {
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setItems(options.map { it.first }.toTypedArray()) { _, index -> options[index].second() }
            .show()
    }

    private fun choice(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isEnabled = canEdit
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        setOnClickListener { onClick() }
    }

    private fun heading(text: String, appearance: Int) = TextView(this).apply {
        this.text = text
        styleAs(appearance)
        setPadding(0, dimen(R.dimen.section_spacing), 0, dimen(R.dimen.space_xxs))
    }

    companion object {
        private const val EXTRA_ID = "ruleId"
        private const val REQUEST_LOCATION = 10
        private const val REQUEST_BLUETOOTH = 11
        private const val DEFAULT_BUDGET_MINUTES = 30
        private const val DEFAULT_BATTERY_PERCENT = 20
        private const val MAX_BATTERY_PERCENT = 100
        private const val DEFAULT_ALLOWANCE_MINUTES = 60
        private val DEFAULT_ACTION = RuleAction(RuleEffect.Block(), RuleTarget.Everything)
        private val DEFAULT_WINDOW_DAYS = setOf(java.time.DayOfWeek.MONDAY)
        private val DEFAULT_WINDOW_START: LocalTime = LocalTime.of(8, 0)
        private val DEFAULT_WINDOW_END: LocalTime = LocalTime.of(15, 0)

        fun intent(context: Context, ruleId: String? = null, childId: DeviceId? = null): Intent =
            RuleTargets.putExtra(
                Intent(context, ContextRuleEditActivity::class.java)
                    .apply { ruleId?.let { putExtra(EXTRA_ID, it) } },
                childId,
            )
    }
}
