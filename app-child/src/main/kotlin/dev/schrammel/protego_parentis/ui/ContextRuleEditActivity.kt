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
import dev.schrammel.protego_parentis.glucose.GlucoseUnit
import dev.schrammel.protego_parentis.glucose.NightscoutStore
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
import dev.schrammel.protego_parentis.policy.RuleTemplates
import dev.schrammel.protego_parentis.policy.RuleWizard
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

    /**
     * The guided flow for a rule being created (rules-UX phase D); null once an
     * existing rule is being edited, or once the parent has opened the full
     * editor from the wizard. [render] branches on this.
     */
    private var wizard: RuleWizard? = null

    /** True while the app picker is choosing a target for the wizard rather than for one of [actions]. */
    private var pickingForWizard: Boolean = false

    /** Set when "edit details" is tapped in the wizard, so the new rule stays in the full editor. */
    private var advancedForNewRule: Boolean = false

    /** The name field, held so a save refused for a blank name can put the cursor where the problem is. */
    private var nameField: EditText? = null

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
        if (pickingForWizard) {
            pickingForWizard = false
            val current = wizard ?: return@registerForActivityResult
            val chosen = if (pickingExcept) {
                RuleTarget.EverythingExcept(picked.map(::PackageName).toSet())
            } else if (picked.isEmpty()) {
                // Nothing picked leaves the question unanswered rather than
                // silently meaning "everything".
                return@registerForActivityResult
            } else {
                mergedTarget(picked.map(::PackageName).toSet(), emptySet())
            }
            wizard = current.chooseTarget(chosen).next()
            render()
            return@registerForActivityResult
        }
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
            glucoseUnit = NightscoutStore(this).units,
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
        // A brand-new rule opens in the guided wizard (rules-UX phase D); an
        // existing one opens in the full editor, where its own shape is already
        // known and re-asking would be busywork. Decided here rather than in
        // [onCreate] so it survives authenticating after the screen is already up,
        // and so "edit details" from the wizard — [advancedForNewRule] — is what
        // stops it coming back.
        if (wizard == null && !advancedForNewRule && intent.getStringExtra(EXTRA_ID) == null && canEdit) {
            wizard = RuleWizard()
        }
        render()
    }

    private fun render() {
        if (wizard != null) {
            renderWizard()
        } else {
            renderAdvanced()
        }
    }

    /**
     * The full editor: every part of the rule at once. A brand-new rule opens
     * in the guided wizard instead (rules-UX phase D), so this is what an
     * existing rule opens in and where "edit details" from the wizard lands.
     */
    private fun renderAdvanced() {
        container.removeAllViews()
        container.addView(heading(getString(R.string.context_edit_title), R.style.TextAppearance_Dementor_Title))
        if (!canEdit) container.addView(readOnlyBanner { requestUnlock { onGatedContentVisible() } })

        addNameField(container)
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
                learnMore = true,
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
                learnMore = true,
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
                learnMore = true,
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
            previewBox()?.let { container.addView(it) }
            container.addView(
                primaryActionButton().apply {
                    setText(R.string.context_save)
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                        .apply { topMargin = dimen(R.dimen.space_xl) }
                    setOnClickListener { saveAdvanced() }
                },
            )
            if (intent.hasExtra(EXTRA_ID)) {
                container.addView(
                    destructiveActionButton().apply {
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

    /**
     * The guided flow a brand-new rule opens in (rules-UX phase D): the three
     * questions almost every rule really asks, one at a time, over the same
     * engine the full editor exposes. The order and the skipping are
     * [RuleWizard]'s, tested without a device; this only draws the step it says
     * is current. A starter from the gallery answers its own questions and lands
     * straight on review.
     */
    private fun renderWizard() {
        val current = wizard ?: return
        container.removeAllViews()
        container.addView(heading(getString(R.string.context_edit_title), R.style.TextAppearance_Dementor_Title))
        if (!canEdit) container.addView(readOnlyBanner { requestUnlock { onGatedContentVisible() } })

        when (current.step) {
            RuleWizard.Step.Start -> renderWizardStart()
            RuleWizard.Step.Effect -> renderWizardEffect(current)
            RuleWizard.Step.Condition -> renderWizardCondition(current)
            RuleWizard.Step.Target -> renderWizardTarget(current)
            RuleWizard.Step.Review -> renderWizardReview(current)
        }

        if (current.canGoBack && canEdit) {
            container.addView(
                actionButton().apply {
                    setText(R.string.context_wizard_back)
                    setOnClickListener { wizard = current.back(); render() }
                },
            )
        }
    }

    /** The gallery: a shape to start from, or the full editor. */
    private fun renderWizardStart() {
        container.addView(bodyText(getString(R.string.context_wizard_start_intro)))
        RuleTemplates.all().forEach { kind ->
            val label = getString(wizardTemplateLabel(kind))
            container.addView(
                choice(label) {
                    name = label
                    wizard = wizard?.applyTemplate(RuleTemplates.spec(kind))
                    render()
                },
            )
        }
        container.addView(
            actionButton().apply {
                setText(R.string.context_wizard_build_own)
                setOnClickListener { wizard = wizard?.buildYourOwn(); render() }
            },
        )
        container.addView(
            actionButton().apply {
                setText(R.string.context_wizard_edit_details)
                setOnClickListener { seedAdvancedFromWizard() }
            },
        )
    }

    /** The starter's own name, so the gallery reads the same here as it does elsewhere. */
    private fun wizardTemplateLabel(kind: RuleTemplates.Kind): Int = when (kind) {
        RuleTemplates.Kind.SCHOOL -> R.string.schedules_starter_school
        RuleTemplates.Kind.HOMEWORK -> R.string.schedules_starter_homework
        RuleTemplates.Kind.DINNER -> R.string.schedules_starter_dinner
        RuleTemplates.Kind.BEDTIME -> R.string.rules_bedtime_name
    }

    private fun renderWizardEffect(current: RuleWizard) {
        container.addView(
            heading(getString(R.string.context_wizard_effect_header), R.style.TextAppearance_Dementor_Section),
        )
        listOf(
            getString(R.string.context_effect_block) to RuleEffect.Block(),
            getString(R.string.context_effect_allow) to RuleEffect.Allow,
            getString(R.string.context_effect_always_block) to RuleEffect.AlwaysBlock(),
            getString(R.string.context_choose_safety_exempt) to RuleEffect.SafetyExempt,
        ).forEach { (label, effect) ->
            container.addView(choice(label) { wizard = current.chooseEffect(effect).next(); render() })
        }
        container.addView(
            actionButton().apply {
                setText(R.string.context_choose_budget)
                setOnClickListener { askForWizardBudget(current) }
            },
        )
    }

    private fun askForWizardBudget(current: RuleWizard) {
        val existingMinutes = (current.effect as? RuleEffect.SetBudget)
            ?.budget?.default?.inWholeMinutes?.toInt()
        sliderDialog(
            title = R.string.context_choose_budget,
            max = LimitSlider.MAX_STEPS,
            initialProgress = LimitSlider.progressFor(existingMinutes ?: DEFAULT_BUDGET_MINUTES),
            format = { formatLimit(LimitSlider.minutesFor(it)) },
        ) { progress ->
            wizard = current.chooseEffect(
                RuleEffect.SetBudget(WeeklyBudget.flat(LimitSlider.minutesFor(progress).minutes)),
            ).next()
            render()
        }
    }

    private fun renderWizardCondition(current: RuleWizard) {
        container.addView(
            heading(getString(R.string.context_wizard_condition_header), R.style.TextAppearance_Dementor_Section),
        )
        // "Always" is the absence of a condition — the simplest answer, one tap
        // away — so it sits on its own above the chooser rather than buried in
        // it. Everything else is one tap further, in the grouped chooser the full
        // editor uses.
        container.addView(
            choice(getString(R.string.context_when_always)) {
                wizard = current.chooseCondition(Condition.Always).next()
                render()
            },
        )
        container.addView(
            choice(getString(R.string.context_wizard_pick_condition)) {
                chooseCondition(current.condition) { condition ->
                    wizard = current.chooseCondition(condition).next()
                    render()
                }
            },
        )
    }

    private fun renderWizardTarget(current: RuleWizard) {
        container.addView(
            heading(getString(R.string.context_wizard_target_header), R.style.TextAppearance_Dementor_Section),
        )
        // The whole "to what" question is four choices — as many as the "what
        // should happen" step above — so they are shown directly rather than
        // behind another menu. Choosing categories or apps hands off to the
        // same pickers the full editor uses.
        container.addView(
            choice(getString(R.string.context_target_everything)) {
                wizard = current.chooseTarget(RuleTarget.Everything).next()
                render()
            },
        )
        container.addView(
            choice(getString(R.string.context_choose_categories)) { chooseWizardCategories(current) },
        )
        container.addView(
            choice(getString(R.string.context_choose_apps)) { pickWizardApps(current, except = false) },
        )
        container.addView(
            choice(getString(R.string.context_choose_everything_except)) { pickWizardApps(current, except = true) },
        )
    }

    private fun pickWizardApps(current: RuleWizard, except: Boolean) {
        pickingForWizard = true
        pickingExcept = except
        val preselect = when (val picked = current.target) {
            is RuleTarget.Apps -> picked.packages.map { it.value }
            is RuleTarget.EverythingExcept -> picked.packages.map { it.value }
            else -> emptyList()
        }
        pickApps.launch(
            AppPickerActivity.intent(
                this,
                getString(if (except) R.string.context_choose_everything_except else R.string.context_choose_apps),
                preselect,
                target.childId,
            ),
        )
    }

    private fun chooseWizardCategories(current: RuleWizard) {
        val categories = policyRuleStore(this, target).categories()
        if (categories.isEmpty()) {
            toast(getString(R.string.rules_no_categories))
            return
        }
        val chosen = mutableSetOf<CategoryId>()
        AlertDialog.Builder(this)
            .setTitle(R.string.context_choose_categories)
            .setMultiChoiceItems(categories.map { it.name }.toTypedArray(), BooleanArray(categories.size)) { _, position, isChecked ->
                val id = CategoryId(categories[position].id)
                if (isChecked) chosen += id else chosen -= id
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                wizard = current.chooseTarget(RuleTarget.Categories(chosen)).next()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun renderWizardReview(current: RuleWizard) {
        container.addView(
            heading(getString(R.string.context_wizard_review_header), R.style.TextAppearance_Dementor_Section),
        )
        previewBox()?.let { container.addView(it) }
        addNameField(container)
        container.addView(
            primaryActionButton().apply {
                setText(R.string.context_wizard_create)
                setOnClickListener { saveWizard() }
            },
        )
    }

    /**
     * Leaves the wizard for the full editor, carrying over whatever the wizard
     * has answered so the parent does not lose the shape they just chose — the
     * point of "edit details" is to refine an answer, not to start over. Only
     * reached before a rule is saved, so the new rule stays in the full editor.
     */
    private fun seedAdvancedFromWizard() {
        val current = wizard ?: return
        advancedForNewRule = true
        wizard = null
        current.condition?.let { whenDraft = ConditionDraft.fromCondition(it) }
        val effect = current.effect
        val chosen = current.target
        if (effect != null && chosen != null) actions = mutableListOf(RuleAction(effect, chosen))
        render()
    }

    /**
     * The rule as it stands, drawn as the same card the rules list uses
     * (rules-UX phase C) — inert: no switch, nowhere to open; it tells a parent
     * what they are building before they save it, which is the review step §D
     * pinned the wizard to and the down-payment §5.H made on it. Built from the
     * wizard's answers when it is up, and from the full editor's fields
     * otherwise; null while too little is chosen to describe.
     */
    private fun previewBox(): View? {
        val current = wizard
        val actionList = if (current != null) {
            val effect = current.effect ?: return null
            val chosen = current.target ?: return null
            listOf(RuleAction(effect, chosen))
        } else {
            actions.toList()
        }
        val rule = ContextRule(
            id = ruleId,
            // A preview exists before the rule is named; the sentence it builds
            // never shows the name, but the model still insists on a non-blank
            // one so a saved rule can be named on the block screen.
            name = name.ifBlank { getString(R.string.context_unnamed_rule) },
            enabled = enabled,
            condition = current?.condition ?: whenDraft.toCondition(),
            actions = actionList,
            exceptWhen = if (current != null) null else exceptDraft?.toCondition(),
        )
        return ruleCard(rule, describer, onOpen = null, onToggle = null)
    }

    /**
     * The name field, shared by the full editor and the wizard's review step.
     * Held in [nameField] so a save refused for a blank name can focus it rather
     * than only telling the parent, in a toast, that something somewhere is wrong.
     */
    private fun addNameField(into: LinearLayout) {
        nameField = EditText(this).apply {
            hint = getString(R.string.context_name_hint)
            setText(name)
            setSingleLine()
            isEnabled = canEdit
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            addTextChangedListener(SimpleWatcher { name = it })
        }
        into.addView(nameField)
    }

    /**
     * Points a refused save at the field that needs it. An inline error on the
     * name box names the problem where it is — and a screen reader reads it out
     * — where a bare toast said what was wrong but not where to fix it
     * (rules-UX phase F).
     */
    private fun flagNameRequired() {
        nameField?.let {
            it.error = getString(R.string.context_name_required)
            it.requestFocus()
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
                    notToggle(group.negated, modeLabel(group)) { checked -> slot.edit { it.setNegated(path, checked) } },
                    removeButton(R.string.context_remove_group) {
                        // A group is a whole subtree, expensive to rebuild: confirm
                        // it where a single condition is cheap enough to just remove.
                        confirmDialog(
                            R.string.context_remove_group,
                            getString(R.string.context_remove_group_confirm),
                            R.string.context_remove_group,
                        ) { slot.edit { it.remove(path) } }
                    },
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
                        notToggle(child.negated, describer.condition(child.condition)) { checked ->
                            slot.edit { it.setNegated(childPath, checked) }
                        },
                        removeButton(R.string.context_remove_condition, describer.condition(child.condition)) {
                            slot.edit { it.remove(childPath) }
                        },
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

    private fun notToggle(checked: Boolean, description: String, onChange: (Boolean) -> Unit) =
        CheckBox(this).apply {
            setText(R.string.context_row_not)
            // The bare "Not" reads the same on every row; name the row it belongs
            // to so a screen reader can tell one from the next.
            contentDescription = getString(R.string.context_row_not_description, description)
            isChecked = checked
            isEnabled = canEdit
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }

    private fun removeButton(textRes: Int, description: String? = null, onClick: () -> Unit) =
        Button(this).apply {
            setText(textRes)
            // "Remove" alone names no row; when the button's own text is not
            // already descriptive, say what it removes.
            if (description != null) {
                contentDescription = getString(R.string.context_remove_description, description)
            }
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
                headingWithInfo(
                    toHeading,
                    R.string.context_to_info_title,
                    R.string.context_to_info_body,
                    learnMore = true,
                )
            } else {
                toHeading
            },
        )
        addView(choice(describer.target(action.target)) { chooseTarget(index) })
        if (actions.size > 1) {
            addView(
                removeButton(R.string.context_remove_action, describer.effect(action.effect)) {
                    actions.removeAt(index); render()
                },
            )
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
        // Grouped, so a chooser that now spans the whole engine reads as a few
        // kinds of question rather than one flat wall. "Always" leads unlabelled:
        // it is the absence of a condition, not a kind of one.
        sectionedMenu(
            R.string.context_when_header,
            listOf(
                null to listOf(
                    getString(R.string.context_when_always) to { onPicked(Condition.Always) },
                ),
                R.string.context_group_time to listOf(
                    getString(R.string.context_choose_window) to { askForWindow(current, onPicked) },
                    getString(R.string.context_choose_allowance) to { askForAllowance(current, onPicked) },
                ),
                R.string.context_group_place to buildList {
                    add(getString(R.string.context_choose_wifi) to { askForWifi(current, onPicked) })
                    places.forEach { place ->
                        add(getString(R.string.context_when_place, place.name) to { onPicked(Condition.AtPlace(place.id)) })
                    }
                },
                R.string.context_group_device to listOf(
                    getString(R.string.context_choose_device) to { chooseDevice(onPicked) },
                    getString(R.string.context_choose_battery) to { askForBattery(current, onPicked) },
                ),
                R.string.context_group_health to listOf(
                    getString(R.string.context_choose_glucose_below) to { askForGlucose(current, below = true, onPicked) },
                    getString(R.string.context_choose_glucose_above) to { askForGlucose(current, below = false, onPicked) },
                    getString(R.string.context_choose_glucose_stale) to { askForGlucoseStale(current, onPicked) },
                ),
            ),
        )
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
     * A glucose level, typed in the family's unit. On a phone that doesn't
     * know the unit (a parent's, or one not yet connected to Nightscout) the
     * unit is asked first rather than guessed: 4 and 72 are both plausible
     * levels, in different units.
     */
    private fun askForGlucose(current: Condition?, below: Boolean, onPicked: (Condition) -> Unit) {
        val unit = NightscoutStore(this).units
        if (unit == null) {
            menu(
                R.string.glucose_unit_title,
                listOf(
                    getString(R.string.glucose_unit_mmol) to { askForGlucose(current, below, GlucoseUnit.MMOL_L, onPicked) },
                    getString(R.string.glucose_unit_mgdl) to { askForGlucose(current, below, GlucoseUnit.MG_DL, onPicked) },
                ),
            )
        } else {
            askForGlucose(current, below, unit, onPicked)
        }
    }

    private fun askForGlucose(current: Condition?, below: Boolean, unit: GlucoseUnit, onPicked: (Condition) -> Unit) {
        val existing = (current as? Condition.GlucoseBelow)?.mgdl?.takeIf { below }
            ?: (current as? Condition.GlucoseAbove)?.mgdl?.takeIf { !below }
        // The "not connected" warning rides in the dialog's own caption rather than
        // as a toast. A toast raised on the line after promptForText() fires as the
        // dialog opens — so it sits behind the dialog and outlives a cancel — while
        // a caption is read next to the field it applies to and leaves with it.
        val notConnected = target.isOwnDevice && !NightscoutStore(this).isConfigured
        promptForText(
            if (below) R.string.context_choose_glucose_below else R.string.context_choose_glucose_above,
            if (unit == GlucoseUnit.MMOL_L) R.string.glucose_hint_mmol else R.string.glucose_hint_mgdl,
            prefill = existing?.let { unit.format(it) }.orEmpty(),
            message = if (notConnected) getString(R.string.glucose_not_connected) else null,
        ) { text ->
            val mgdl = unit.parse(text)
            if (mgdl == null) {
                toast(getString(R.string.glucose_invalid))
            } else {
                onPicked(if (below) Condition.GlucoseBelow(mgdl) else Condition.GlucoseAbove(mgdl))
            }
        }
    }

    /** How long without a reading counts as "no data": 5 minutes to 4 hours, in 5-minute steps. */
    private fun askForGlucoseStale(current: Condition?, onPicked: (Condition) -> Unit) {
        val existing = (current as? Condition.GlucoseStale)?.minutes ?: DEFAULT_GLUCOSE_STALE_MINUTES
        sliderDialog(
            title = R.string.context_choose_glucose_stale,
            max = MAX_GLUCOSE_STALE_STEPS,
            initialProgress = (existing / GLUCOSE_STALE_STEP - 1).coerceIn(0, MAX_GLUCOSE_STALE_STEPS),
            format = { getString(R.string.context_when_glucose_stale, (it + 1) * GLUCOSE_STALE_STEP) },
        ) { progress -> onPicked(Condition.GlucoseStale((progress + 1) * GLUCOSE_STALE_STEP)) }
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

    private fun saveAdvanced() {
        if (name.isBlank()) {
            flagNameRequired()
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
     * Saves the rule the wizard's three answers describe. The wizard has already
     * guaranteed an effect, a condition and a target, so the only thing left to
     * refuse is the name — the same questions the full editor asks, in the same
     * order, so a starter saved from the gallery and one saved from the form
     * cannot disagree about what "looks like a mistake".
     */
    private fun saveWizard() {
        val current = wizard ?: return
        if (name.isBlank()) {
            flagNameRequired()
            return
        }
        val rule = current.toRule(ruleId, name.trim(), enabled) ?: return
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

    /**
     * A choice drawn as a quiet, full-width row — the current condition, effect or
     * target, a starter in the gallery, or "Add condition". A raised button reads
     * as the one thing to press, so a tree of them turns the editor into a wall of
     * buttons and crowds each row against its Not and Remove controls. Flat,
     * left-aligned and ripple-touched reads as "tap to change" without competing
     * with Save. Still a [Button] so the screen's choices and its real actions
     * keep one focus and accessibility story.
     */
    private fun choice(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isEnabled = canEdit
        isAllCaps = false
        gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        withRipple()
        val pad = dimen(R.dimen.space_sm)
        setPadding(pad, pad, pad, pad)
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
        private const val DEFAULT_GLUCOSE_STALE_MINUTES = 30
        private const val GLUCOSE_STALE_STEP = 5
        private const val MAX_GLUCOSE_STALE_STEPS = 47
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
