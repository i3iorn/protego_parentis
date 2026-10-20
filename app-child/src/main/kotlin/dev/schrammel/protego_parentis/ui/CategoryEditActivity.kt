package dev.schrammel.protego_parentis.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.audit.Audit
import dev.schrammel.protego_parentis.model.AuditAction
import dev.schrammel.protego_parentis.model.AuditActor
import dev.schrammel.protego_parentis.model.Category
import dev.schrammel.protego_parentis.model.CategoryId
import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.model.Defaults
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.RuleEffect
import dev.schrammel.protego_parentis.model.RuleTarget
import dev.schrammel.protego_parentis.model.WeeklyBudget
import dev.schrammel.protego_parentis.policy.CategoryHierarchy
import dev.schrammel.protego_parentis.rules.LocalPolicyStore
import dev.schrammel.protego_parentis.rules.StoredCategory
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.ui.rules.RuleScope
import dev.schrammel.protego_parentis.ui.rules.RuleTargets
import dev.schrammel.protego_parentis.ui.rules.appCatalog
import dev.schrammel.protego_parentis.ui.rules.categories
import dev.schrammel.protego_parentis.ui.rules.categoryBudgetMinutes
import dev.schrammel.protego_parentis.ui.rules.categoryBudgetRuleIdFor
import dev.schrammel.protego_parentis.ui.rules.contextRuleRuleStore
import dev.schrammel.protego_parentis.ui.rules.delete
import dev.schrammel.protego_parentis.ui.rules.deleteCategory
import dev.schrammel.protego_parentis.ui.rules.policyRuleStore
import dev.schrammel.protego_parentis.ui.rules.upsert
import dev.schrammel.protego_parentis.ui.rules.upsertCategory
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

/**
 * Creates or edits one category: a name, a daily limit, and the apps in it —
 * on whichever kind of phone edits [target]'s rules (#43).
 *
 * An app may be picked here and in any number of other categories — membership is
 * many-to-many by design, and every category it lands in is enforced separately.
 *
 * The limit is a shortcut over the same rule engine every other restriction
 * goes through, not a field of its own: saving writes an ordinary [ContextRule]
 * — [Condition.Always], [RuleEffect.SetBudget], a single-category
 * [RuleTarget.Categories] — at [LocalPolicyStore.MIGRATED_CATEGORY_BUDGET_ID_PREFIX]'s
 * well-known per-category id, the same slot [LocalPolicyStore.migrateAppRulesToUnifiedRules]
 * converts an old-format category limit into. [StoredCategory.limitMinutes]
 * itself is written as 0 from here on — see [AppLimitsActivity]'s own doc
 * comment for why this screen still keeps [StoredCategory] for the category's
 * name, membership, and nesting.
 */
class CategoryEditActivity : ParentGatedActivity() {

    private lateinit var target: RuleScope

    private var existing: StoredCategory? = null
    private var categoryId: String = ""
    private var packages = linkedSetOf<String>()
    private var nested = linkedSetOf<String>()
    private var limitMinutes: Int = 0

    private lateinit var nameInput: EditText
    private lateinit var appsSummary: TextView
    private lateinit var nestedSummary: TextView

    private val defaultLimitMinutes = Defaults.CATEGORY_LIMIT.inWholeMinutes.toInt()

    private val pickApps = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        packages = LinkedHashSet(AppPickerActivity.resultFrom(result.data))
        refreshAppsSummary()
    }

    override fun supportsReadOnly() = true

    private var loaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val resolved = RuleTargets.resolve(this) ?: return finish()
        target = resolved
    }

    /**
     * [categoryId]'s budget as this screen's [ContextRule] says it, or null if there is none.
     * Delegates to [dev.schrammel.protego_parentis.ui.rules.categoryBudgetMinutes] —
     * shared with every other screen that edits a migrated rule by its
     * well-known id, so the id scheme has one place to change.
     */
    private fun minutesFrom(rules: List<ContextRule>, categoryId: String): Int? =
        categoryBudgetMinutes(rules, categoryId)

    private fun ruleId(categoryId: String) = categoryBudgetRuleIdFor(categoryId)

    /**
     * Loaded once. A read-only viewer who unlocks mid-visit calls this again
     * (see [readOnlyBanner] below) and must see the same category and id it
     * already resolved — not a second [store] read racing whatever [save] or
     * [pickApps] have since done to the in-memory state.
     */
    override fun onGatedContentVisible() {
        if (!requireStillInFamily(target)) return
        if (loaded) {
            // A rebuild must not cost the reader what they have typed: the
            // name field is rebuilt from the stored category, which is older
            // than anything typed since.
            val typedName = if (::nameInput.isInitialized) nameInput.text.toString() else ""
            showColumn(buildLayout(existing))
            if (typedName.isNotEmpty()) nameInput.setText(typedName)
            refreshAppsSummary()
            refreshNestedSummary()
            return
        }

        Background.load(this, {
            val store = policyRuleStore(this, target)
            val found = intent.getStringExtra(EXTRA_ID)?.let { id -> store.categories().firstOrNull { it.id == id } }
            // The rule is the live source now; the stored field is a fallback
            // for a category not yet migrated (or a device mid-upgrade)
            // rather than the everyday path.
            val rules = contextRuleRuleStore(this, target).read()
            found to rules
        }) { (found, rules) -> applyLoaded(found, rules) }
    }

    /**
     * Fills the screen with what was loaded.
     *
     * Only the first result may fill it. [onGatedContentVisible] runs on every
     * [onResume], and each run queues a read on the one background thread the
     * whole app shares, so a second read can be in flight when the first one
     * lands: the screen appears, the reader drags the limit slider, and then
     * the second result arrives and quietly replaces what they chose with the
     * value it read a moment earlier. A limit that silently reverts to "No
     * limit" on its way to being saved is worse than one that took a moment to
     * appear, and the save that follows writes the reverted value — deleting
     * the rule rather than creating it.
     *
     * Internal so the test can drive the second arrival directly.
     */
    internal fun applyLoaded(found: StoredCategory?, rules: List<ContextRule>) {
        if (loaded) return
        loaded = true
        existing = found
        categoryId = found?.id ?: UUID.randomUUID().toString()
        packages = LinkedHashSet(found?.packages.orEmpty())
        nested = LinkedHashSet(found?.subcategories.orEmpty())
        limitMinutes = minutesFrom(rules, categoryId) ?: found?.limitMinutes ?: defaultLimitMinutes

        showColumn(buildLayout(existing))
        refreshAppsSummary()
        refreshNestedSummary()
    }

    private fun buildLayout(existing: StoredCategory?): LinearLayout {
        val content = contentColumn()

        content.addView(
            screenTitle(
                getString(R.string.category_title),
                R.string.category_info_title,
                R.string.category_info_body,
            ),
        )
        if (!canEdit) content.addView(readOnlyBanner { requestUnlock { onGatedContentVisible() } })

        // The limit below is an ordinary ContextRule under the hood (see this
        // class's own doc comment), governed by the same precedence a nested
        // category or a per-app rule elsewhere can override. The "how rules
        // combine" explainer now lives only beside the condition tree it
        // describes (rules-UX phase F).

        nameInput = EditText(this).apply {
            hint = getString(R.string.category_name_hint)
            setText(existing?.name.orEmpty())
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            isEnabled = canEdit
        }
        content.addView(nameInput)

        content.addView(heading(getString(R.string.category_limit_header), 20f))
        content.addView(
            limitSliderBlock(
                initialMinutes = limitMinutes,
                onChanged = { minutes -> limitMinutes = minutes },
            ).apply {
                (getChildAt(1) as? SeekBar)?.isEnabled = canEdit
            },
        )

        content.addView(heading(getString(R.string.category_apps_header), 20f))
        appsSummary = TextView(this).apply { styleAs(R.style.TextAppearance_Dementor_Body) }
        content.addView(appsSummary)

        if (canEdit) {
            content.addView(
                Button(this).apply {
                    setText(R.string.category_choose_apps)
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                        .apply { topMargin = dimen(R.dimen.space_sm) }
                    setOnClickListener {
                        pickApps.launch(
                            AppPickerActivity.intent(
                                this@CategoryEditActivity,
                                getString(R.string.category_apps_header),
                                packages,
                                target.childId,
                            ),
                        )
                    }
                },
            )
        }

        content.addView(
            headingWithInfo(
                heading(getString(R.string.category_nested_header), 20f),
                R.string.category_nested_info_title,
                R.string.category_nested_info_body,
            ),
        )
        content.addView(
            TextView(this).apply {
                setText(R.string.category_nested_hint)
                styleAs(R.style.TextAppearance_Dementor_Caption)
            },
        )
        nestedSummary = TextView(this).apply { styleAs(R.style.TextAppearance_Dementor_Body) }
        content.addView(nestedSummary)
        if (canEdit) {
            content.addView(
                Button(this).apply {
                    setText(R.string.category_choose_nested)
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                        .apply { topMargin = dimen(R.dimen.space_sm) }
                    setOnClickListener { chooseNested() }
                },
            )

            content.addView(
                Button(this).apply {
                    setText(R.string.category_save)
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                        .apply { topMargin = dimen(R.dimen.section_spacing) }
                    setOnClickListener { save() }
                },
            )

            if (existing != null) {
                content.addView(
                    Button(this).apply {
                        setText(R.string.category_delete)
                        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                            .apply { topMargin = dimen(R.dimen.space_sm) }
                        setOnClickListener {
                            confirmDialog(
                                existing.name,
                                getString(R.string.category_delete_confirm),
                                R.string.category_delete,
                            ) { deleteCategory(existing.name) }
                        }
                    },
                )
            }
        }

        return content
    }

    private fun deleteCategory(name: String) {
        policyRuleStore(this, target).deleteCategory(categoryId)
        // Otherwise a budget rule survives naming a category that no longer
        // exists — harmless to PolicyEngine, which only builds a status for
        // categories still in the list, but confusing clutter on the unified
        // rules screen.
        contextRuleRuleStore(this, target).delete(ruleId(categoryId))
        Audit.record(
            context = this,
            actor = AuditActor.PARENT,
            action = AuditAction.CATEGORY_DELETED,
            subject = name,
        )
        finish()
    }

    private fun save() {
        val name = nameInput.text.toString().trim()
        if (name.isEmpty()) {
            toast(R.string.category_name_required)
            return
        }

        val minutes = limitMinutes
        // Both writes have to land for the limit to be a limit: the category
        // carries the name and its members, the rule carries the allowance. A
        // write that could not be made at all (a ruleset this phone may not
        // write, or one it holds no copy of) must not be acknowledged as one
        // that is on its way to the child's phone.
        val categorySaved = policyRuleStore(this, target).upsertCategory(
            StoredCategory(
                id = categoryId,
                name = name,
                // Vestigial as of this rewrite: the real budget is the
                // ContextRule below, the same way an app's own limit no
                // longer lives on appLimits either.
                limitMinutes = 0,
                packages = packages.toSet(),
                subcategories = nested.toSet(),
            ),
        )

        val ruleStore = contextRuleRuleStore(this, target)
        val id = ruleId(categoryId)
        val ruleSaved = if (minutes > 0) {
            ruleStore.upsert(
                ContextRule(
                    id = id,
                    name = name,
                    enabled = true,
                    condition = Condition.Always,
                    effect = RuleEffect.SetBudget(WeeklyBudget.flat(minutes.minutes)),
                    target = RuleTarget.Categories(setOf(CategoryId(categoryId))),
                ),
            )
        } else {
            ruleStore.delete(id)
        }

        if (!categorySaved || !ruleSaved) {
            // Nothing was written, so nothing is on its way. Saying so is the
            // whole difference between a parent who can tell the change did
            // not stick and one left believing it did.
            toast(R.string.category_save_failed)
            return
        }

        Audit.record(
            context = this,
            actor = AuditActor.PARENT,
            action = AuditAction.CATEGORY_SAVED,
            subject = name,
            after = Audit.minutes(minutes),
        )
        // Same acknowledgement ContextRuleEditActivity gives for the identical
        // situation: this writes to the same remotely-synced stores, and "same
        // button, same instant finish()" would otherwise look identical to a
        // local save that already took effect.
        acknowledgeRuleWrite(policyRuleStore(this, target))
        finish()
    }

    /**
     * Offers every other category, minus any whose selection would create a loop.
     *
     * Refusing up front rather than accepting and silently ignoring it later: a
     * parent who builds "Fun holds Games, Games holds Fun" should be told, not
     * left with a hierarchy that quietly does something else.
     */
    private fun chooseNested() {
        val store = policyRuleStore(this, target)
        val all = store.categories().filter { it.id != categoryId }
        if (all.isEmpty()) {
            toast(R.string.category_no_nested)
            return
        }

        // Evaluate against the hierarchy as it would be with this edit applied.
        val pending = store.categories().map { candidate ->
            if (candidate.id == categoryId) candidate.copy(subcategories = nested) else candidate
        }.map(::toDomain)

        val selectable = all.filterNot { candidate ->
            CategoryHierarchy.wouldCreateCycle(
                categories = pending,
                parent = CategoryId(categoryId),
                child = CategoryId(candidate.id),
            )
        }
        if (selectable.isEmpty()) {
            toast(R.string.category_no_nested)
            return
        }

        val names = selectable.map { it.name }.toTypedArray()
        val checked = selectable.map { it.id in nested }.toBooleanArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.category_nested_header)
            .setMultiChoiceItems(names, checked) { _, index, isChecked ->
                val id = selectable[index].id
                if (isChecked) nested += id else nested -= id
            }
            .setPositiveButton(android.R.string.ok) { _, _ -> refreshNestedSummary() }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                // Restore, since the listener has already mutated the set.
                nested = LinkedHashSet(
                    store.categories().firstOrNull { it.id == categoryId }?.subcategories.orEmpty(),
                )
                refreshNestedSummary()
            }
            .show()
    }

    private fun toDomain(stored: StoredCategory) = Category(
        id = CategoryId(stored.id),
        name = stored.name,
        budget = WeeklyBudget.UNLIMITED,
        packages = stored.packages.map(::PackageName).toSet(),
        subcategories = stored.subcategories.map(::CategoryId).toSet(),
    )

    private fun refreshNestedSummary() {
        val byId = policyRuleStore(this, target).categories().associateBy { it.id }
        nestedSummary.text = nested
            .mapNotNull { byId[it]?.name }
            .sorted()
            .joinToString(", ")
            .ifBlank { getString(R.string.category_no_nested) }
    }

    private fun refreshAppsSummary() {
        val catalog = appCatalog(this, target)
        appsSummary.text = when {
            packages.isEmpty() -> getString(R.string.category_no_apps)
            else -> packages
                .map { catalog.labelFor(PackageName(it)) }
                .sorted()
                .joinToString(", ")
        }
    }

    private fun heading(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        setPadding(0, dimen(R.dimen.screen_padding), 0, dimen(R.dimen.space_xs))
    }

    companion object {
        private const val EXTRA_ID = "categoryId"

        fun intent(context: Context, categoryId: String? = null, childId: DeviceId? = null): Intent =
            RuleTargets.putExtra(
                Intent(context, CategoryEditActivity::class.java)
                    .apply { categoryId?.let { putExtra(EXTRA_ID, it) } },
                childId,
            )
    }
}
