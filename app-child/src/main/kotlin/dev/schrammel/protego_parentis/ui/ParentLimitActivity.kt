package dev.schrammel.protego_parentis.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.audit.Audit
import dev.schrammel.protego_parentis.model.AuditAction
import dev.schrammel.protego_parentis.model.AuditActor
import dev.schrammel.protego_parentis.sync.DailySummaryStore
import dev.schrammel.protego_parentis.sync.SyncCoordinator
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.ui.rules.RuleTarget
import dev.schrammel.protego_parentis.ui.rules.RuleTargets
import dev.schrammel.protego_parentis.ui.rules.policyRuleStore

/**
 * Changing the daily allowance from a parent's own phone.
 *
 * The edit itself is a replicated write, same as every other rule (#104): it
 * merges with whatever else changed on [target] rather than racing a
 * dedicated message against the rest of the policy, and the confirmation
 * says "sent" rather than "changed" only because it still has to reach the
 * child's phone over the network to take effect there.
 *
 * The slider starts from the currently replicated limit when this phone has
 * one, falling back to the last daily summary otherwise — a family whose
 * other phones are still on an older build, or a phone that has never
 * exchanged state with [target], holds no replicated policy at all yet.
 */
class ParentLimitActivity : ParentRoleActivity() {

    private lateinit var target: RuleTarget
    private lateinit var sync: SyncCoordinator
    private lateinit var summaries: DailySummaryStore

    override fun onParentPhoneReady(savedInstanceState: Bundle?) {
        val resolved = RuleTargets.resolve(this) ?: return finish()
        target = resolved
        sync = SyncCoordinator(this)
        summaries = DailySummaryStore(this)

        val column = contentColumn()
        column.addView(screenTitle(getString(R.string.parent_limit_title)))

        if (!sync.settings().isUsable) {
            column.addView(bodyText(getString(R.string.parent_not_connected_hint)))
            showColumn(column)
            return
        }

        column.addView(bodyText(getString(R.string.parent_limit_body)))

        val replicated = policyRuleStore(this, target).read()?.dailyLimitMinutes
        val known = replicated ?: summaries.latest?.allowedMinutes ?: 0
        var chosenMinutes = known
        column.addView(
            limitSliderBlock(initialMinutes = known, onChanged = { minutes -> chosenMinutes = minutes }),
        )

        column.addView(
            Button(this).apply {
                setText(R.string.parent_limit_send)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dimen(R.dimen.space_md) }
                setOnClickListener { send(chosenMinutes) }
            },
        )
        if (replicated == null && summaries.latest == null) {
            column.addView(captionText(getString(R.string.parent_limit_unknown)))
        }
        showColumn(column)
    }

    private fun send(minutes: Int) {
        val snapshot = policyRuleStore(this, target).read() ?: return
        policyRuleStore(this, target).write(snapshot.copy(dailyLimitMinutes = minutes))
        Audit.record(
            context = this,
            actor = AuditActor.PARENT,
            action = AuditAction.DAILY_LIMIT_CHANGED,
            after = Audit.minutes(minutes),
        )
        toast(R.string.parent_limit_sent)
        finish()
    }

    companion object {
        fun intent(context: Context, childId: DeviceId? = null) =
            RuleTargets.putExtra(Intent(context, ParentLimitActivity::class.java), childId)
    }
}
