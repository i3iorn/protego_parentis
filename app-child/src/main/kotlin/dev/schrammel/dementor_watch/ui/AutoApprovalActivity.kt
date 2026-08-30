package dev.schrammel.dementor_watch.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import dev.schrammel.dementor_watch.R
import dev.schrammel.dementor_watch.audit.Audit
import dev.schrammel.dementor_watch.model.AuditAction
import dev.schrammel.dementor_watch.model.AuditActor
import dev.schrammel.dementor_watch.model.AutoApproval
import dev.schrammel.dementor_watch.model.AutoApprovalWindow
import dev.schrammel.dementor_watch.rules.AutoApprovalStore
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

/** The family's standing answers (RQ-1). */
class AutoApprovalActivity : ParentGatedActivity() {

    private lateinit var store: AutoApprovalStore
    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AutoApprovalStore(this)
        column = contentColumn()
        showColumn(column)
    }

    override fun onGatedContentVisible() = render()

    override fun onGatedContentHidden() {
        column.removeAllViews()
    }

    private fun render() {
        column.removeAllViews()
        column.addView(screenTitle(getString(R.string.auto_title)))
        column.addView(bodyText(getString(R.string.auto_intro)))

        val rules = store.rules.sortedBy { it.name.lowercase() }
        if (rules.isEmpty()) {
            column.addView(bodyText(getString(R.string.auto_empty)))
        } else {
            column.addView(group(rules.map(::row)))
        }

        column.addView(
            actionButton().apply {
                setText(R.string.auto_add)
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                    .apply { topMargin = dimen(R.dimen.space_md) }
                setOnClickListener { startActivity(AutoApprovalEditActivity.intent(context)) }
            },
        )
    }

    private fun row(rule: AutoApproval) = navigationRow(
        title = rule.name,
        subtitle = summarise(this, rule),
    ) { startActivity(AutoApprovalEditActivity.intent(this, rule.id)) }

    companion object {
        fun intent(context: Context) = Intent(context, AutoApprovalActivity::class.java)

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

/** Editing one standing answer. */
class AutoApprovalEditActivity : ParentGatedActivity() {

    private lateinit var store: AutoApprovalStore
    private lateinit var column: LinearLayout
    private lateinit var ruleId: String
    private lateinit var nameInput: EditText

    private var minutes: Int = DEFAULT_MINUTES
    private var count: Int = DEFAULT_COUNT
    private var window: AutoApprovalWindow = AutoApprovalWindow.WEEK
    private var enabled: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AutoApprovalStore(this)
        ruleId = intent.getStringExtra(EXTRA_ID) ?: UUID.randomUUID().toString()

        store.find(ruleId)?.let { existing ->
            minutes = existing.maxAmount.inWholeMinutes.toInt()
            count = existing.maxPerWindow
            window = existing.window
            enabled = existing.enabled
        }
        nameInput = EditText(this).apply {
            hint = getString(R.string.auto_name_hint)
            setText(store.find(ruleId)?.name.orEmpty())
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
        column.addView(screenTitle(getString(R.string.auto_title)))

        column.addView(sectionHeader(getString(R.string.auto_name_header)))
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
                setOnCheckedChangeListener { _, value -> enabled = value }
            },
        )

        column.addView(
            actionButton().apply {
                setText(R.string.auto_save)
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                    .apply { topMargin = dimen(R.dimen.space_md) }
                setOnClickListener { save() }
            },
        )

        if (store.find(ruleId) != null) {
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
        store.upsert(rule)
        Audit.record(
            context = this,
            actor = AuditActor.PARENT,
            action = AuditAction.AUTO_RULE_SAVED,
            subject = name,
            after = Audit.minutes(minutes),
        )
        finish()
    }

    private fun delete() {
        val name = store.find(ruleId)?.name.orEmpty()
        store.delete(ruleId)
        Audit.record(
            context = this,
            actor = AuditActor.PARENT,
            action = AuditAction.AUTO_RULE_DELETED,
            subject = name,
        )
        finish()
    }

    companion object {
        private const val EXTRA_ID = "ruleId"
        private const val DEFAULT_MINUTES = 15
        private const val DEFAULT_COUNT = 2
        private const val MAX_MINUTES = 60
        private const val MAX_COUNT = 20

        fun intent(context: Context, ruleId: String? = null) =
            Intent(context, AutoApprovalEditActivity::class.java).apply {
                ruleId?.let { putExtra(EXTRA_ID, it) }
            }
    }
}
