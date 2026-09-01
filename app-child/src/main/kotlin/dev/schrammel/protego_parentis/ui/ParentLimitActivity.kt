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

/**
 * Changing the daily allowance from a parent's own phone.
 *
 * Like [ParentBonusActivity], this is a message, not a local change: the phone
 * this runs on has no rules of its own to edit. It arrives on the child's phone
 * at the next poll and applies there the same way an edit made standing at that
 * phone would, which is why the confirmation says "sent" rather than "changed".
 *
 * The slider starts from the limit last reported in a daily summary rather than
 * zero, since this phone keeps no copy of the child's rules — that figure can be
 * a day stale, or absent from a phone that has never connected, so it is offered
 * as a starting point rather than presented as certain.
 */
class ParentLimitActivity : ParentRoleActivity() {

    private lateinit var sync: SyncCoordinator
    private lateinit var summaries: DailySummaryStore

    override fun onParentPhoneReady(savedInstanceState: Bundle?) {
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

        val known = summaries.latest?.allowedMinutes ?: 0
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
        if (summaries.latest == null) {
            column.addView(captionText(getString(R.string.parent_limit_unknown)))
        }
        showColumn(column)
    }

    private fun send(minutes: Int) {
        sync.publishSetDailyLimit(minutes)
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
        fun intent(context: Context) = Intent(context, ParentLimitActivity::class.java)
    }
}
