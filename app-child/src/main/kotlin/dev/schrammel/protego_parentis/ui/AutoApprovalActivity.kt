package dev.schrammel.protego_parentis.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.audit.Audit
import dev.schrammel.protego_parentis.model.AuditAction
import dev.schrammel.protego_parentis.model.AuditActor
import dev.schrammel.protego_parentis.model.AutoApproval
import dev.schrammel.protego_parentis.model.AutoApprovalWindow
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.ui.rules.autoApprovalRuleStore
import dev.schrammel.protego_parentis.ui.rules.delete
import dev.schrammel.protego_parentis.ui.rules.find
import dev.schrammel.protego_parentis.ui.rules.upsert
import dev.schrammel.protego_parentis.ui.rules.RuleScope
import dev.schrammel.protego_parentis.ui.rules.RuleTargets
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

/**
 * The family's standing answers (RQ-1), on whichever kind of phone edits
 * [target]'s rules (#89 — every other rule kind had already grown this
 * remote/local split; this was the one gap left).
 *
 * Answering a request in the moment stays own-device-only, standing at the
 * phone the request came from — see [RulesActivity]'s "Requests" group,
 * which this screen does not belong to. Deciding when a standing yes should
 * apply is a rule about the child's phone, not an action on it, so it moves
 * with every other rule.
 */
class AutoApprovalActivity : ParentGatedActivity() {

    private lateinit var target: RuleScope
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
        column.addView(screenTitle(getString(R.string.auto_title)))
        column.addView(bodyText(getString(R.string.auto_intro)))
        if (!canEdit) column.addView(readOnlyBanner { requestUnlock { onGatedContentVisible() } })

        val rules = autoApprovalRuleStore(this, target).read().sortedBy { it.name.lowercase() }
        if (rules.isEmpty()) {
            column.addView(bodyText(getString(R.string.auto_empty)))
        } else {
            column.addView(group(rules.map(::row)))
        }

        if (canEdit) {
            column.addView(
                actionButton().apply {
                    setText(R.string.auto_add)
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                        .apply { topMargin = dimen(R.dimen.space_md) }
                    setOnClickListener { startActivity(AutoApprovalEditActivity.intent(context, childId = target.childId)) }
                },
            )
        }
    }

    private fun row(rule: AutoApproval) = navigationRow(
        title = rule.name,
        subtitle = summarise(this, rule),
    ) { startActivity(AutoApprovalEditActivity.intent(this, rule.id, target.childId)) }

    companion object {
        fun intent(context: Context, childId: DeviceId? = null) =
            RuleTargets.putExtra(Intent(context, AutoApprovalActivity::class.java), childId)

        /**
         * A rule in one line of plain English.
         *
         * This is the sentence a parent will actually audit, so it is built from
         * the model rather than assembled from whatever the editor last showed.
         */
        fun summarise(context: Context, rule: AutoApproval): String {
            val times = context.resources.getQuantityString(
                when (rule.window) {
                    AutoApprovalWindow.DAY -> R.plurals.auto_times_day
                    AutoApprovalWindow.WEEK -> R.plurals.auto_times_week
                },
                rule.maxPerWindow,
                rule.maxPerWindow,
            )
            val amount = context.formatLimit(rule.maxAmount.inWholeMinutes.toInt())
            return context.getString(
                if (rule.enabled) R.string.auto_row else R.string.auto_row_off,
                amount,
                times,
            )
        }
    }
}

/** Editing one standing answer, on whichever kind of phone edits [target]'s rules (#89). */
class AutoApprovalEditActivity : ParentGatedActivity() {

    private lateinit var target: RuleScope
    private lateinit var column: LinearLayout
    private lateinit var ruleId: String
    private lateinit var nameInput: EditText
    private var existing: AutoApproval? = null

    private var minutes: Int = DEFAULT_MINUTES
    private var count: Int = DEFAULT_COUNT
    private var window: AutoApprovalWindow = AutoApprovalWindow.WEEK
    private var enabled: Boolean = true

    override fun supportsReadOnly() = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val resolved = RuleTargets.resolve(this) ?: return finish()
        target = resolved
        ruleId = intent.getStringExtra(EXTRA_ID) ?: UUID.randomUUID().toString()

        val found = autoApprovalRuleStore(this, target).find(ruleId)
        existing = found
        found?.let { rule ->
            minutes = rule.maxAmount.inWholeMinutes.toInt()
            count = rule.maxPerWindow
            window = rule.window
            enabled = rule.enabled
        }
        nameInput = EditText(this).apply {
            hint = getString(R.string.auto_name_hint)
            setText(found?.name.orEmpty())
        }

        column = contentColumn()
        showColumn(column)
    }

    override fun onGatedContentVisible() {
        if (!requireStillInFamily(target)) return
        render()
    }

    private fun render() {
        column.removeAllViews()
        column.addView(
            headingWithInfo(
                screenTitle(getString(R.string.auto_title)),
                R.string.auto_info_title,
                R.string.auto_info_body,
            ),
        )
        if (!canEdit) column.addView(readOnlyBanner { requestUnlock { onGatedContentVisible() } })

        column.addView(sectionHeader(getString(R.string.auto_name_header)))
        nameInput.isEnabled = canEdit
        column.addView(nameInput)

        column.addView(sectionHeader(getString(R.string.auto_amount_header)))
        column.addView(slider(minutes, MAX_MINUTES) { minutes = it })

        column.addView(sectionHeader(getString(R.string.auto_count_header)))
        column.addView(slider(count, MAX_COUNT, isMinutes = false) { count = it })
        column.addView(
            CheckBox(this).apply {
                setText(
                    if (window == AutoApprovalWindow.WEEK) {
                        R.string.auto_window_week
                    } else {
                        R.string.auto_window_day
                    },
                )
                isChecked = window == AutoApprovalWindow.WEEK
                isEnabled = canEdit
                setOnCheckedChangeListener { _, weekly ->
                    window = if (weekly) AutoApprovalWindow.WEEK else AutoApprovalWindow.DAY
                    render()
                }
            },
        )

        column.addView(
            CheckBox(this).apply {
                setText(R.string.auto_enabled)
                isChecked = enabled
                isEnabled = canEdit
                setOnCheckedChangeListener { _, value -> enabled = value }
            },
        )

        if (canEdit) {
            column.addView(
                actionButton().apply {
                    setText(R.string.auto_save)
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                        .apply { topMargin = dimen(R.dimen.space_md) }
                    setOnClickListener { save() }
                },
            )

            if (existing != null) {
                column.addView(
                    actionButton().apply {
                        setText(R.string.auto_delete)
                        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                            .apply { topMargin = dimen(R.dimen.space_sm) }
                        setOnClickListener { delete() }
                    },
                )
            }
        }
    }

    private fun slider(
        current: Int,
        max: Int,
        isMinutes: Boolean = true,
        onChanged: (Int) -> Unit,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL

        val label = TextView(context).apply {
            styleAs(R.style.TextAppearance_Dementor_Metric)
            text = if (isMinutes) formatLimit(current) else current.toString()
        }
        addView(label)
        addView(
            SeekBar(context).apply {
                this.max = max - 1
                progress = current - 1
                isEnabled = canEdit
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                        val value = progress + 1
                        label.text = if (isMinutes) formatLimit(value) else value.toString()
                        if (fromUser) onChanged(value)
                    }

                    override fun onStartTrackingTouch(bar: SeekBar) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar) = Unit
                })
            },
        )
    }

    private fun save() {
        val name = nameInput.text.toString().trim()
        if (name.isEmpty()) {
            toast(R.string.auto_needs_name)
            return
        }

        val rule = AutoApproval(
            id = ruleId,
            name = name,
            enabled = enabled,
            maxAmount = minutes.minutes,
            maxPerWindow = count,
            window = window,
        )
        autoApprovalRuleStore(this, target).upsert(rule)
        Audit.record(
            context = this,
            actor = AuditActor.PARENT,
            action = AuditAction.AUTO_RULE_SAVED,
            subject = name,
            after = Audit.minutes(minutes),
        )
        // Same acknowledgement ContextRuleEditActivity gives for the identical
        // situation: this writes to the same remotely-synced store.
        if (!target.isOwnDevice) toast(getString(R.string.context_save_sent_remote))
        finish()
    }

    private fun delete() {
        val name = existing?.name.orEmpty()
        autoApprovalRuleStore(this, target).delete(ruleId)
        Audit.record(
            context = this,
            actor = AuditActor.PARENT,
            action = AuditAction.AUTO_RULE_DELETED,
            subject = name,
        )
        if (!target.isOwnDevice) toast(getString(R.string.context_delete_sent_remote))
        finish()
    }

    companion object {
        private const val EXTRA_ID = "ruleId"
        private const val DEFAULT_MINUTES = 15
        private const val DEFAULT_COUNT = 2
        private const val MAX_MINUTES = 60
        private const val MAX_COUNT = 20

        fun intent(context: Context, ruleId: String? = null, childId: DeviceId? = null) =
            RuleTargets.putExtra(
                Intent(context, AutoApprovalEditActivity::class.java).apply {
                    ruleId?.let { putExtra(EXTRA_ID, it) }
                },
                childId,
            )
    }
}
