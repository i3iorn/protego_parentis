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
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.audit.Audit
import dev.schrammel.protego_parentis.model.AuditAction
import dev.schrammel.protego_parentis.model.AuditActor
import androidx.appcompat.app.AlertDialog
import dev.schrammel.protego_parentis.model.Category
import dev.schrammel.protego_parentis.model.CategoryId
import dev.schrammel.protego_parentis.model.Defaults
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.WeeklyBudget
import dev.schrammel.protego_parentis.policy.CategoryHierarchy
import dev.schrammel.protego_parentis.rules.StoredCategory
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.ui.rules.appCatalog
import dev.schrammel.protego_parentis.ui.rules.categories
import dev.schrammel.protego_parentis.ui.rules.deleteCategory
import dev.schrammel.protego_parentis.ui.rules.labelFor
import dev.schrammel.protego_parentis.ui.rules.policyRuleStore
import dev.schrammel.protego_parentis.ui.rules.upsertCategory
import dev.schrammel.protego_parentis.ui.rules.RuleScope
import dev.schrammel.protego_parentis.ui.rules.RuleTargets
import java.util.UUID

/**
 * Creates or edits one category: a name, a daily limit, and the apps in it —
 * on whichever kind of phone edits [target]'s rules (#43).
 *
 * An app may be picked here and in any number of other categories — membership is
 * many-to-many by design, and every category it lands in is enforced separately.
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val resolved = RuleTargets.resolve(this) ?: return finish()
        target = resolved

        val store = policyRuleStore(this, target)
        val found = intent.getStringExtra(EXTRA_ID)?.let { id -> store.categories().firstOrNull { it.id == id } }
        existing = found
        categoryId = found?.id ?: UUID.randomUUID().toString()
        packages = LinkedHashSet(found?.packages.orEmpty())
        nested = LinkedHashSet(found?.subcategories.orEmpty())
        limitMinutes = found?.limitMinutes ?: defaultLimitMinutes
    }

    override fun onGatedContentVisible() {
        if (!requireStillInFamily(target)) return
        showColumn(buildLayout(existing))
        refreshAppsSummary()
        refreshNestedSummary()
    }

    private fun buildLayout(existing: StoredCategory?): LinearLayout {
        val content = contentColumn()

        content.addView(heading(getString(R.string.category_title), 28f))
        if (!canEdit) content.addView(readOnlyBanner { requestUnlock { onGatedContentVisible() } })

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

        content.addView(heading(getString(R.string.category_nested_header), 20f))
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
                            policyRuleStore(this@CategoryEditActivity, target).deleteCategory(categoryId)
                            Audit.record(
                                context = this@CategoryEditActivity,
                                actor = AuditActor.PARENT,
                                action = AuditAction.CATEGORY_DELETED,
                                subject = existing.name,
                            )
                            finish()
                        }
                    },
                )
            }
        }

        return content
    }

    private fun save() {
        val name = nameInput.text.toString().trim()
        if (name.isEmpty()) {
            toast(R.string.category_name_required)
            return
        }

        val minutes = limitMinutes
        policyRuleStore(this, target).upsertCategory(
            StoredCategory(
                id = categoryId,
                name = name,
                limitMinutes = minutes,
                packages = packages.toSet(),
                subcategories = nested.toSet(),
            ),
        )
        Audit.record(
            context = this,
            actor = AuditActor.PARENT,
            action = AuditAction.CATEGORY_SAVED,
            subject = name,
            after = Audit.minutes(minutes),
        )
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
