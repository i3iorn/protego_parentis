package dev.schrammel.protego_parentis.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.format.formatDuration
import dev.schrammel.protego_parentis.audit.Audit
import dev.schrammel.protego_parentis.model.AuditAction
import dev.schrammel.protego_parentis.model.AuditActor
import dev.schrammel.protego_parentis.model.CategoryId
import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.DeviceTag
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.model.Place
import dev.schrammel.protego_parentis.model.PlaceId
import dev.schrammel.protego_parentis.model.PlaceRef
import dev.schrammel.protego_parentis.model.RecurringWindow
import dev.schrammel.protego_parentis.model.RuleEffect
import dev.schrammel.protego_parentis.model.RuleTarget
import dev.schrammel.protego_parentis.model.WeeklyBudget
import dev.schrammel.protego_parentis.rules.ContextRuleStore
import dev.schrammel.protego_parentis.sync.SyncCoordinator
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.ui.rules.categories
import dev.schrammel.protego_parentis.ui.rules.contextRuleRuleStore
import dev.schrammel.protego_parentis.ui.rules.delete
import dev.schrammel.protego_parentis.ui.rules.find
import dev.schrammel.protego_parentis.ui.rules.placeRuleStore
import dev.schrammel.protego_parentis.ui.rules.policyRuleStore
import dev.schrammel.protego_parentis.ui.rules.upsert
import dev.schrammel.protego_parentis.ui.rules.RuleScope
import dev.schrammel.protego_parentis.ui.rules.RuleTargets
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
 */
class ContextRuleEditActivity : ParentGatedActivity() {

    private lateinit var target: RuleScope
    private lateinit var container: LinearLayout

    private var ruleId: String = ""
    private var name: String = ""
    private var enabled: Boolean = true
    private var negate: Boolean = false
    private var condition: Condition = Condition.Always

    /**
     * Extra conditions beyond [condition] (#87) — empty for the common case of
     * a rule with just one. Combined with [condition] via [combineAll] only at
     * save time ([combinedCondition]); [Condition.AllOf]/[Condition.AnyOf]
     * themselves never appear as [condition] while this screen is open.
     */
    private var extraConditions: MutableList<Condition> = mutableListOf()

    /** Whether [condition] plus [extraConditions] must all hold, or any one of them. */
    private var combineAll: Boolean = true
    private var effect: RuleEffect = RuleEffect.Block()
    private var ruleTarget: RuleTarget = RuleTarget.Everything

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
        if (pickingExcept) {
            ruleTarget = RuleTarget.EverythingExcept(picked.map(::PackageName).toSet())
        } else if (picked.isNotEmpty()) {
            ruleTarget = mergedTarget(picked.map(::PackageName).toSet(), ruleTarget.targetCategories())
        }
        render()
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
        effect = existing?.effect ?: RuleEffect.Block()
        ruleTarget = existing?.target ?: RuleTarget.Everything
        // A negated condition is stored wrapped; unwrap so the toggle reflects it.
        // A combined one (#87) is stored as one AllOf/AnyOf; unwrap that too, into
        // the first part plus whatever else it was combining, so editing a rule
        // built from more than one condition round-trips through the same rows
        // that built it, rather than showing one condition and silently losing
        // the rest.
        val stored = existing?.condition ?: Condition.Always
        negate = stored is Condition.Not
        when (val unwrapped = if (stored is Condition.Not) stored.condition else stored) {
            is Condition.AllOf -> {
                combineAll = true
                condition = unwrapped.conditions.firstOrNull() ?: Condition.Always
                extraConditions = unwrapped.conditions.drop(1).toMutableList()
            }

            is Condition.AnyOf -> {
                combineAll = false
                condition = unwrapped.conditions.firstOrNull() ?: Condition.Always
                extraConditions = unwrapped.conditions.drop(1).toMutableList()
            }

            else -> condition = unwrapped
        }

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
        container.addView(
            choice(describeCondition(condition)) {
                chooseCondition(condition) { picked -> condition = picked; render() }
            },
        )

        // #87: a second condition and beyond, combined with this first one —
        // not just with each other — by either needing every one of them or
        // just one. Placed directly under the conditions rather than between
        // the first one and "invert" below: this picker and that condition
        // row are not two separate settings, one governs how all the rows
        // above it are combined, first one included.
        if (extraConditions.isNotEmpty()) {
            container.addView(
                choice(getString(if (combineAll) R.string.context_combine_all else R.string.context_combine_any)) {
                    menu(
                        R.string.context_combine_header,
                        listOf(
                            getString(R.string.context_combine_all) to { combineAll = true; render() },
                            getString(R.string.context_combine_any) to { combineAll = false; render() },
                        ),
                    )
                },
            )
            extraConditions.forEachIndexed { index, extra ->
                container.addView(
                    LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                        addView(
                            choice(describeCondition(extra)) {
                                chooseCondition(extra) { picked -> extraConditions[index] = picked; render() }
                            }.apply { layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f) },
                        )
                        addView(
                            Button(this@ContextRuleEditActivity).apply {
                                setText(R.string.context_remove_condition)
                                isEnabled = canEdit
                                setOnClickListener { extraConditions.removeAt(index); render() }
                            },
                        )
                    },
                )
            }
        }
        container.addView(
            choice(getString(R.string.context_add_condition)) {
                chooseCondition(null) { picked -> extraConditions.add(picked); render() }
            },
        )
        // Below every condition row rather than under the first one: this
        // negates the combined result of all of them (see [combinedCondition]),
        // not just the row directly above it.
        container.addView(
            CheckBox(this).apply {
                setText(R.string.context_invert)
                isChecked = negate
                isEnabled = canEdit
                setOnCheckedChangeListener { _, checked ->
                    negate = checked
                    render()
                }
            },
        )

        // ---- do what ----
        container.addView(
            headingWithInfo(
                heading(getString(R.string.context_do_header), R.style.TextAppearance_Dementor_Section),
                R.string.context_do_info_title,
                R.string.context_do_info_body,
            ),
        )
        container.addView(choice(describeEffect()) { chooseEffect() })

        // ---- to whom ----
        container.addView(
            headingWithInfo(
                heading(getString(R.string.context_to_header), R.style.TextAppearance_Dementor_Section),
                R.string.context_to_info_title,
                R.string.context_to_info_body,
            ),
        )
        container.addView(choice(describeTarget()) { chooseTarget() })

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
                            val deleted = name
                            contextRuleRuleStore(this@ContextRuleEditActivity, target).delete(ruleId)
                            Audit.record(
                                context = this@ContextRuleEditActivity,
                                actor = AuditActor.PARENT,
                                action = AuditAction.CONTEXT_RULE_DELETED,
                                subject = deleted,
                            )
                            if (!target.isOwnDevice) toast(getString(R.string.context_delete_sent_remote))
                            finish()
                        }
                    },
                )
            }
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

    private fun chooseDevice(onPicked: (Condition) -> Unit) {
        requestBluetoothIfNeeded()
        promptForText(
            R.string.context_choose_device,
            R.string.context_device_hint,
            message = getString(R.string.context_device_explainer),
        ) { tag -> onPicked(Condition.NearDevice(DeviceTag(tag))) }
    }

    private fun chooseEffect() {
        if (!canEdit) return
        menu(
            R.string.context_do_header,
            listOf(
                getString(R.string.context_effect_block) to { effect = RuleEffect.Block(); render() },
                getString(R.string.context_effect_allow) to { effect = RuleEffect.Allow; render() },
                getString(R.string.context_effect_always_block) to { effect = RuleEffect.AlwaysBlock(); render() },
                getString(R.string.context_choose_safety_exempt) to { effect = RuleEffect.SafetyExempt; render() },
                getString(R.string.context_choose_budget) to { askForBudget(effect) },
            ),
        )
    }

    private fun askForBudget(current: RuleEffect?) {
        val existingMinutes = (current as? RuleEffect.SetBudget)
            ?.budget?.default?.inWholeMinutes?.toInt()
        sliderDialog(
            title = R.string.context_choose_budget,
            max = LimitSlider.MAX_STEPS,
            initialProgress = LimitSlider.progressFor(existingMinutes ?: DEFAULT_BUDGET_MINUTES),
            format = { formatLimit(LimitSlider.minutesFor(it)) },
        ) { progress ->
            effect = RuleEffect.SetBudget(
                WeeklyBudget.flat(LimitSlider.minutesFor(progress).minutes),
            )
            render()
        }
    }

    private fun chooseTarget() {
        if (!canEdit) return
        menu(
            R.string.context_to_header,
            listOf(
                getString(R.string.context_target_everything) to {
                    ruleTarget = RuleTarget.Everything
                    render()
                },
                getString(R.string.context_choose_categories) to { chooseCategories() },
                getString(R.string.context_choose_apps) to {
                    pickingExcept = false
                    pickApps.launch(
                        AppPickerActivity.intent(
                            this,
                            getString(R.string.context_choose_apps),
                            ruleTarget.targetApps().map { it.value },
                            target.childId,
                        ),
                    )
                },
                getString(R.string.context_choose_everything_except) to {
                    pickingExcept = true
                    val current = (ruleTarget as? RuleTarget.EverythingExcept)?.packages.orEmpty()
                    pickApps.launch(
                        AppPickerActivity.intent(
                            this,
                            getString(R.string.context_choose_everything_except),
                            current.map { it.value },
                            target.childId,
                        ),
                    )
                },
            ),
        )
    }

    private fun chooseCategories() {
        val categories = policyRuleStore(this, target).categories()
        if (categories.isEmpty()) {
            toast(getString(R.string.rules_no_categories))
            return
        }
        val selected = ruleTarget.targetCategories().map { it.value }
        val checked = categories.map { it.id in selected }.toBooleanArray()
        val chosen = selected.toMutableSet()

        AlertDialog.Builder(this)
            .setTitle(R.string.context_choose_categories)
            .setMultiChoiceItems(
                categories.map { it.name }.toTypedArray(),
                checked,
            ) { _, index, isChecked ->
                val id = categories[index].id
                if (isChecked) chosen += id else chosen -= id
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                ruleTarget = mergedTarget(ruleTarget.targetApps(), chosen.map(::CategoryId).toSet())
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun save() {
        if (name.isBlank()) {
            toast(getString(R.string.context_name_required))
            return
        }
        val rule = ContextRule(
            id = ruleId,
            name = name.trim(),
            enabled = enabled,
            condition = combinedCondition(),
            effect = effect,
            target = ruleTarget,
        )
        if (looksLikeTotalBlackout(rule)) {
            val bodyRes = if (rule.effect is RuleEffect.AlwaysBlock) {
                R.string.context_blackout_body_always_block
            } else {
                R.string.context_blackout_body
            }
            confirmDialog(R.string.context_blackout_title, bodyRes, R.string.context_save) { commit(rule) }
            return
        }
        commit(rule)
    }

    /**
     * The one contradiction the tutorial itself warns about, made hard to miss
     * at the moment it's easy to create: an unconditioned block of literally
     * everything, with nothing anywhere in this ruleset carved back out. Not a
     * general contradiction checker — AllOf/AnyOf combinations and overlapping
     * budgets are still on the parent to notice — just this one well-known
     * shape, which #87's combine/invert controls make easy to build by
     * accident.
     *
     * A plain [RuleEffect.Block] is only a true blackout while no [RuleEffect.Allow]
     * rule carves an exception back out; a [RuleEffect.AlwaysBlock] is not
     * saved by an [RuleEffect.Allow] at all — that is the whole point of the
     * override — so only a [RuleEffect.SafetyExempt] rule counts as its
     * exception. Getting this wrong in either direction would either warn a
     * parent who has already carved out an exception that works, or, worse,
     * silently let an always-block-everything rule through believing an
     * ordinary allow rule will save some app from it when it cannot.
     *
     * Internal rather than private, so [ContextRuleEditActivityTest] can pin
     * both branches directly, without driving the "Do this" menu's dialog.
     */
    internal fun looksLikeTotalBlackout(rule: ContextRule): Boolean {
        if (!rule.enabled) return false
        if (rule.target != RuleTarget.Everything) return false
        if (rule.condition != Condition.Always) return false
        val others = contextRuleRuleStore(this, target).read().filterNot { it.id == rule.id }
        return when (rule.effect) {
            is RuleEffect.Block -> others.none { it.enabled && it.effect is RuleEffect.Allow }
            is RuleEffect.AlwaysBlock -> others.none { it.enabled && it.effect is RuleEffect.SafetyExempt }
            else -> false
        }
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
        if (!target.isOwnDevice) toast(getString(R.string.context_save_sent_remote))
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

    // ---------- descriptions ----------

    /**
     * One condition, as a phrase — never negated here even when [negate] is
     * set, since #87 lets [negate] apply to a whole group of these rather than
     * to just the first one; the "Invert" checkbox is the one place that's said.
     */
    private fun describeCondition(current: Condition): String = when (current) {
        is Condition.Always -> getString(R.string.context_when_always)
        is Condition.OnWifi -> getString(R.string.context_when_wifi, current.ssid)
        is Condition.NearDevice -> getString(R.string.context_when_near, current.tag.value)
        is Condition.AtPlace -> getString(
            R.string.context_when_place,
            places().firstOrNull { it.id == current.place }?.name
                ?: getString(R.string.context_place_unknown),
        )

        is Condition.BatteryBelow -> getString(R.string.context_when_battery, current.percent)

        is Condition.DuringWindow -> getString(
            if (current.window.parityAnchor == null) {
                R.string.context_when_window
            } else {
                R.string.context_when_window_alternating
            },
            DayPicker.describe(this, current.window.days),
            getString(
                R.string.schedules_window,
                formatTime(current.window.start),
                formatTime(current.window.end),
            ),
        )

        is Condition.AllowanceSpent -> getString(R.string.context_when_allowance_spent, current.minutes)

        is Condition.Not -> getString(R.string.context_when_not, describeCondition(current.condition))

        is Condition.AllOf -> current.conditions
            .joinToString(getString(R.string.context_join_and)) { describeCondition(it) }
            .ifBlank { getString(R.string.context_when_always) }

        is Condition.AnyOf -> current.conditions
            .joinToString(getString(R.string.context_join_or)) { describeCondition(it) }
            .ifBlank { getString(R.string.context_when_never) }
    }

    /**
     * [condition] plus [extraConditions], combined the way [combineAll] and
     * [negate] say they should be — the single [Condition] this rule actually
     * saves and evaluates against.
     */
    private fun combinedCondition(): Condition {
        val parts = listOf(condition) + extraConditions
        val combined: Condition = when {
            parts.size == 1 -> parts.single()
            combineAll -> Condition.AllOf(parts)
            else -> Condition.AnyOf(parts)
        }
        return if (negate) Condition.Not(combined) else combined
    }

    private fun describeEffect(): String = when (val current = effect) {
        is RuleEffect.Block -> getString(R.string.context_effect_block)
        is RuleEffect.Allow -> getString(R.string.context_effect_allow)
        is RuleEffect.AlwaysBlock -> getString(R.string.context_effect_always_block)
        is RuleEffect.SafetyExempt -> getString(R.string.context_effect_safety_exempt)
        is RuleEffect.SetBudget ->
            getString(R.string.context_effect_budget, formatDuration(current.budget.default))
    }

    private fun describeTarget(): String = when (val current = ruleTarget) {
        is RuleTarget.Everything -> getString(R.string.context_target_everything)
        is RuleTarget.Apps -> describeApps(current.packages)
        is RuleTarget.Categories -> describeCategories(current.categories)
        is RuleTarget.Mixed ->
            getString(R.string.context_target_mixed, describeApps(current.packages), describeCategories(current.categories))
        is RuleTarget.EverythingExcept ->
            getString(R.string.context_target_everything_except, describeApps(current.packages))
    }

    private fun describeApps(packages: Set<PackageName>): String =
        resources.getQuantityString(R.plurals.rules_category_apps, packages.size, packages.size)

    private fun describeCategories(categories: Set<CategoryId>): String = categories
        .mapNotNull { id -> policyRuleStore(this, target).categories().firstOrNull { it.id == id.value }?.name }
        .joinToString(", ")
        .ifBlank { getString(R.string.context_target_categories) }

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

/**
 * Names the places rules can refer to — on whichever kind of phone edits
 * [target]'s rules (#43). Renaming, resizing or deleting an existing place
 * flows the same way on both: [PlaceRuleStore] edits it in place, with no
 * coordinate ever involved. Creating one is the one asymmetry, and it is
 * about who *holds* the new coordinate rather than who has one worth saving:
 * [renderLocal] writes straight into [ContextRuleStore], while [renderRemote]
 * — wherever the phone is standing right now, or typed by hand for somewhere
 * neither phone is — always goes out as a
 * [dev.schrammel.protego_parentis.sync.core.SyncMessageType.PLACE_CREATE_REQUESTED]
 * for the managed phone to actually create.
 */
class PlacesActivity : ParentGatedActivity() {

    private lateinit var target: RuleScope
    private lateinit var container: LinearLayout

    override fun supportsReadOnly() = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val resolved = RuleTargets.resolve(this) ?: return finish()
        target = resolved

        container = contentColumn()
        showColumn(container)
    }

    override fun onGatedContentVisible() {
        if (!requireStillInFamily(target)) return
        render()
    }

    private fun render() {
        container.removeAllViews()
        container.addView(
            TextView(this).apply {
                setText(R.string.places_title)
                styleAs(R.style.TextAppearance_Dementor_Title)
            },
        )
        container.addView(
            TextView(this).apply {
                setText(R.string.places_explainer)
                styleAs(R.style.TextAppearance_Dementor_Caption)
            },
        )
        if (!canEdit) container.addView(readOnlyBanner { requestUnlock { onGatedContentVisible() } })

        if (target.isOwnDevice) renderLocal() else renderRemote()
    }

    // ---------- the managed phone editing its own places: unchanged, coordinates and all ----------

    private fun renderLocal() {
        val store = ContextRuleStore(this)
        val places = store.places
        val clampedIds = store.clampedPlaceIds
        if (places.isEmpty()) {
            container.addView(
                TextView(this).apply {
                    setText(R.string.places_none)
                    styleAs(R.style.TextAppearance_Dementor_Body)
                    setPadding(0, dimen(R.dimen.space_md), 0, 0)
                },
            )
        }
        addCollapsibleRows(
            container,
            places.map { place ->
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        TextView(context).apply {
                            text = getString(R.string.places_row, place.name, place.radiusMetres)
                            styleAs(R.style.TextAppearance_Dementor_ListItem)
                            setPadding(0, dimen(R.dimen.space_sm), 0, dimen(R.dimen.space_sm))
                            if (canEdit) setOnClickListener { confirmDelete(store, place) }
                        },
                    )
                    if (place.id in clampedIds) {
                        addView(
                            TextView(context).apply {
                                setText(R.string.places_radius_increased)
                                styleAs(R.style.TextAppearance_Dementor_Caption)
                                setPadding(0, 0, 0, dimen(R.dimen.space_sm))
                            },
                        )
                    }
                }
            },
        )

        if (canEdit) {
            container.addView(
                Button(this).apply {
                    setText(R.string.places_add_here)
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                        .apply { topMargin = dimen(R.dimen.space_lg) }
                    setOnClickListener {
                        addMyPosition { name, latitude, longitude ->
                            store.upsertPlace(
                                Place(
                                    id = PlaceId(UUID.randomUUID().toString()),
                                    name = name,
                                    latitude = latitude,
                                    longitude = longitude,
                                    radiusMetres = DEFAULT_RADIUS_METRES,
                                ),
                            )
                            render()
                        }
                    }
                },
            )
            container.addView(
                Button(this).apply {
                    setText(R.string.places_add_by_coordinates)
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                        .apply { topMargin = dimen(R.dimen.space_sm) }
                    setOnClickListener {
                        addByCoordinates { name, latitude, longitude, radiusMetres ->
                            store.upsertPlace(
                                Place(
                                    id = PlaceId(UUID.randomUUID().toString()),
                                    name = name,
                                    latitude = latitude,
                                    longitude = longitude,
                                    radiusMetres = radiusMetres,
                                ),
                            )
                            render()
                        }
                    }
                },
            )
        }
    }

    /**
     * Names *this* phone's current position — the managed phone's own, in
     * [renderLocal], or a parent's, in [addPositionRemotely]. Either way it is
     * a fix taken on whichever phone is running this code right now, never
     * one requested from, or sent to, another device: [onConfirmed] is what
     * decides whether that becomes a local place or a
     * [dev.schrammel.protego_parentis.sync.core.SyncMessageType.PLACE_CREATE_REQUESTED]
     * asking the managed phone to create one from it.
     */
    @SuppressLint("MissingPermission")
    private fun addMyPosition(onConfirmed: (name: String, latitude: Double, longitude: Double) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_LOCATION,
            )
            return
        }

        val manager = getSystemService(LocationManager::class.java)
        val fix = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { runCatching { manager?.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }

        if (fix == null) {
            Toast.makeText(this, R.string.places_no_fix, Toast.LENGTH_LONG).show()
            return
        }

        promptForText(
            R.string.places_add_here,
            R.string.places_name_hint,
            positiveText = R.string.places_add_here,
        ) { name ->
            onConfirmed(name, fix.latitude, fix.longitude)
        }
    }

    /**
     * Names a place you are not standing in — "school", added from the kitchen
     * table (#31). Coordinates are typed rather than picked from a map: a map
     * means a tile-fetching third-party service, which the README's "no
     * third-party SDKs" promise rules out (see the issue's own discussion for
     * why). [checkOnMap] hands off to whatever map app is already installed
     * instead, so a typo is still catchable before saving.
     *
     * [onConfirmed] runs once every field has passed validation: on the
     * managed phone itself that means writing the place straight into
     * [ContextRuleStore], and from a parent's phone it means asking the
     * managed phone to create it instead — see [addPlaceRemotely].
     */
    private fun addByCoordinates(onConfirmed: (name: String, latitude: Double, longitude: Double, radiusMetres: Int) -> Unit) {
        val nameInput = EditText(this).apply {
            hint = getString(R.string.places_name_hint)
            setSingleLine()
        }
        val latInput = EditText(this).apply {
            hint = getString(R.string.places_lat_hint)
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL or
                InputType.TYPE_NUMBER_FLAG_SIGNED
            setSingleLine()
        }
        val lonInput = EditText(this).apply {
            hint = getString(R.string.places_lon_hint)
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL or
                InputType.TYPE_NUMBER_FLAG_SIGNED
            setSingleLine()
        }
        val radiusInput = EditText(this).apply {
            hint = getString(R.string.places_radius_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(DEFAULT_RADIUS_METRES.toString())
            setSingleLine()
        }
        val checkOnMapButton = Button(this).apply {
            setText(R.string.places_check_map)
            setOnClickListener { checkOnMap(latInput, lonInput) }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dimen(R.dimen.screen_padding)
            setPadding(pad, pad, pad, 0)
            addView(nameInput)
            addView(latInput)
            addView(lonInput)
            addView(radiusInput)
            addView(checkOnMapButton)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.places_add_by_coordinates)
            .setView(content)
            .setPositiveButton(R.string.places_add_by_coordinates, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        // A plain setPositiveButton closure dismisses on any input, valid or
        // not. Wiring the click after the dialog is showing is what lets an
        // invalid field Toast and leave the dialog open instead.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                addByCoordinatesConfirmed(nameInput, latInput, lonInput, radiusInput, dialog, onConfirmed)
            }
        }
        dialog.show()
    }

    private fun addByCoordinatesConfirmed(
        nameInput: EditText,
        latInput: EditText,
        lonInput: EditText,
        radiusInput: EditText,
        dialog: AlertDialog,
        onConfirmed: (name: String, latitude: Double, longitude: Double, radiusMetres: Int) -> Unit,
    ) {
        val name = nameInput.text.toString().trim()
        val latitude = latInput.text.toString().toDoubleOrNull()
        val longitude = lonInput.text.toString().toDoubleOrNull()
        val radiusMetres = radiusInput.text.toString().toIntOrNull()

        if (name.isEmpty()) {
            Toast.makeText(this, R.string.places_name_required, Toast.LENGTH_LONG).show()
            return
        }
        if (latitude == null || latitude !in -90.0..90.0 || longitude == null || longitude !in -180.0..180.0) {
            Toast.makeText(this, R.string.places_bad_coordinates, Toast.LENGTH_LONG).show()
            return
        }
        if (radiusMetres == null || radiusMetres < Place.MINIMUM_RADIUS_METRES) {
            Toast.makeText(
                this,
                getString(R.string.places_bad_radius, Place.MINIMUM_RADIUS_METRES),
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        onConfirmed(name, latitude, longitude, radiusMetres)
        dialog.dismiss()
    }

    /** Hands off to whatever map app is installed, so a typo is catchable before saving. */
    private fun checkOnMap(latInput: EditText, lonInput: EditText) {
        val latitude = latInput.text.toString().toDoubleOrNull()
        val longitude = lonInput.text.toString().toDoubleOrNull()
        if (latitude == null || latitude !in -90.0..90.0 || longitude == null || longitude !in -180.0..180.0) {
            Toast.makeText(this, R.string.places_bad_coordinates, Toast.LENGTH_LONG).show()
            return
        }

        val uri = Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.places_no_map_app, Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmDelete(store: ContextRuleStore, place: Place) {
        // Rules referring to it go too, rather than being left permanently
        // unsatisfiable.
        confirmDialog(place.name, getString(R.string.places_delete_warning), R.string.places_delete) {
            store.deletePlace(place.id)
            render()
        }
    }

    // ---------- a parent editing another device's places: rename/resize/delete, plus creating one remotely ----------

    private fun renderRemote() {
        val store = placeRuleStore(this, target)
        val refs = store.read()
        if (refs.isEmpty()) {
            container.addView(
                TextView(this).apply {
                    setText(R.string.places_none)
                    styleAs(R.style.TextAppearance_Dementor_Body)
                    setPadding(0, dimen(R.dimen.space_md), 0, 0)
                },
            )
        }
        addCollapsibleRows(
            container,
            refs.map { ref ->
                TextView(this).apply {
                    text = getString(R.string.places_row, ref.name, ref.radiusMetres)
                    styleAs(R.style.TextAppearance_Dementor_ListItem)
                    setPadding(0, dimen(R.dimen.space_sm), 0, dimen(R.dimen.space_sm))
                    if (canEdit) setOnClickListener { editRemotePlace(store, ref) }
                }
            },
        )

        if (canEdit) {
            container.addView(
                Button(this).apply {
                    setText(R.string.places_add_my_position)
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                        .apply { topMargin = dimen(R.dimen.space_lg) }
                    setOnClickListener { addPositionRemotely() }
                },
            )
            container.addView(
                Button(this).apply {
                    setText(R.string.places_add_by_coordinates)
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                        .apply { topMargin = dimen(R.dimen.space_sm) }
                    setOnClickListener { addPlaceRemotely() }
                },
            )
        }
    }

    /**
     * Asks the managed phone to create a place at *this* — the parent's own —
     * current position, for the ordinary case of naming somewhere the parent
     * is standing and the child is not, like a grandparent's house on a
     * visit. [addMyPosition] takes the fix on whichever phone calls it, so
     * this reaches the parent's own [LocationManager], never the managed
     * phone's — that's [dev.schrammel.protego_parentis.enforcement.EnforcementService]'s
     * territory, and unrelated to this button entirely.
     */
    private fun addPositionRemotely() {
        addMyPosition { name, latitude, longitude ->
            SyncCoordinator(this).publishPlaceCreateRequest(name, latitude, longitude, DEFAULT_RADIUS_METRES)
            Toast.makeText(this, R.string.places_create_requested, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Asks the managed phone to create a place at coordinates typed here,
     * for a place neither phone is currently standing in — [PlaceRuleStore]
     * has nothing between "read" and "rename/resize/delete" for exactly that
     * reason, and [addPositionRemotely] only ever offers wherever the parent
     * already is. The new place will not appear in [renderRemote]'s list
     * until the managed phone confirms it and that reaches this phone
     * through the ordinary place-ref replication, so this only tells the
     * parent the request was sent, not that it landed.
     */
    private fun addPlaceRemotely() {
        addByCoordinates { name, latitude, longitude, radiusMetres ->
            SyncCoordinator(this).publishPlaceCreateRequest(name, latitude, longitude, radiusMetres)
            Toast.makeText(this, R.string.places_create_requested, Toast.LENGTH_LONG).show()
        }
    }

    private fun editRemotePlace(store: dev.schrammel.protego_parentis.ui.rules.PlaceRuleStore, ref: PlaceRef) {
        AlertDialog.Builder(this)
            .setTitle(ref.name)
            .setItems(
                arrayOf(
                    getString(R.string.places_rename),
                    getString(R.string.places_resize),
                    getString(R.string.places_delete),
                ),
            ) { _, index ->
                when (index) {
                    0 -> promptForText(R.string.places_rename, R.string.places_name_hint, prefill = ref.name) { name ->
                        store.rename(ref.id, name)
                        render()
                    }
                    1 -> sliderDialog(
                        title = R.string.places_resize,
                        max = MAX_RADIUS_METRES,
                        initialProgress = ref.radiusMetres,
                        format = { getString(R.string.places_radius_value, it) },
                    ) { radius ->
                        store.resize(ref.id, radius.coerceAtLeast(Place.MINIMUM_RADIUS_METRES))
                        render()
                    }
                    2 -> confirmDialog(ref.name, getString(R.string.places_delete_warning), R.string.places_delete) {
                        store.delete(ref.id)
                        render()
                    }
                }
            }
            .show()
    }

    companion object {
        private const val REQUEST_LOCATION = 12
        private const val DEFAULT_RADIUS_METRES = 150
        private const val MAX_RADIUS_METRES = 5000

        fun intent(context: Context, childId: DeviceId? = null) =
            RuleTargets.putExtra(Intent(context, PlacesActivity::class.java), childId)
    }
}
