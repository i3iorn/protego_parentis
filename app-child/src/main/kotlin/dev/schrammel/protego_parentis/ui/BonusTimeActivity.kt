package dev.schrammel.protego_parentis.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.enforcement.EnforcementService
import dev.schrammel.protego_parentis.model.CategoryId
import dev.schrammel.protego_parentis.model.GrantTarget
import dev.schrammel.protego_parentis.audit.Audit
import dev.schrammel.protego_parentis.model.AuditAction
import dev.schrammel.protego_parentis.model.AuditActor
import dev.schrammel.protego_parentis.rules.GrantStore
import dev.schrammel.protego_parentis.rules.TargetLabels
import dev.schrammel.protego_parentis.rules.LocalPolicyStore
import java.time.Instant

/**
 * Grants extra time for today.
 *
 * The spec calls this the highest-frequency parent action in the whole product,
 * so it is deliberately two taps and nothing more. Bonus time expires at the day
 * boundary and never changes the underlying limit.
 */
class BonusTimeActivity : ParentGatedActivity() {

    private lateinit var store: LocalPolicyStore
    private lateinit var grants: GrantStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = LocalPolicyStore(this)
        grants = GrantStore(this)
        showColumn(buildLayout())
    }

    private fun buildLayout(): LinearLayout {
        val content = contentColumn()

        content.addView(heading(getString(R.string.bonus_title), 28f))
        content.addView(
            TextView(this).apply {
                setText(R.string.bonus_hint)
                styleAs(R.style.TextAppearance_Dementor_Caption)
                setPadding(0, dimen(R.dimen.space_xxs), 0, dimen(R.dimen.space_sm))
            },
        )

        content.addView(heading(getString(R.string.bonus_device_header), 20f))
        content.addView(amountRow { minutes -> grant(GrantTarget.Device, minutes) })

        val categories = store.categories
        if (categories.isNotEmpty()) {
            content.addView(heading(getString(R.string.bonus_category_header), 20f))
            categories.sortedBy { it.name.lowercase() }.forEach { category ->
                content.addView(
                    TextView(this).apply {
                        text = category.name
                        styleAs(R.style.TextAppearance_Dementor_ListItem)
                        setPadding(0, dimen(R.dimen.space_md), 0, dimen(R.dimen.space_hairline))
                    },
                )
                content.addView(
                    amountRow { minutes ->
                        grant(GrantTarget.Category(CategoryId(category.id)), minutes)
                    },
                )
            }
        }

        content.addView(
            Button(this).apply {
                setText(R.string.bonus_clear)
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                    .apply { topMargin = dimen(R.dimen.row_icon) }
                setOnClickListener {
                    grants.clearAll()
                    EnforcementService.start(this@BonusTimeActivity)
                    toast(getString(R.string.bonus_cleared))
                }
            },
        )

        return content
    }

    private fun amountRow(onPick: (Int) -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            bonusOptions().forEach { minutes ->
                addView(
                    Button(context).apply {
                        text = getString(R.string.bonus_add_minutes, minutes)
                        layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                            .apply { marginEnd = dimen(R.dimen.space_xs) }
                        setOnClickListener { onPick(minutes) }
                    },
                )
            }
        }

    private fun grant(target: GrantTarget, minutes: Int) {
        val policy = store.load()
        grants.add(
            target = target,
            minutes = minutes,
            now = Instant.now(),
            zone = policy.timeZone,
            dayBoundaryHour = policy.dayBoundaryHour,
        )
        Audit.record(
            context = this,
            actor = AuditActor.PARENT,
            action = AuditAction.BONUS_GRANTED,
            subject = TargetLabels.describe(this, target),
            after = Audit.minutes(minutes),
        )
        // Apply immediately: the point of bonus time is that it unblocks now.
        EnforcementService.start(this)
        toast(resources.getQuantityString(R.plurals.bonus_granted, minutes, minutes))
    }

    /** See `res/values/config.xml`. */
    private fun bonusOptions(): List<Int> =
        resources.getIntArray(R.array.bonus_minute_options).toList()

    private fun heading(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        setPadding(0, dimen(R.dimen.space_xl), 0, dimen(R.dimen.space_xs))
    }

    companion object {
        fun intent(context: Context) = Intent(context, BonusTimeActivity::class.java)
    }
}
