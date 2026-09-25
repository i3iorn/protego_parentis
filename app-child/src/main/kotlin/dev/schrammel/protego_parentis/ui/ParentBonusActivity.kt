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
import dev.schrammel.protego_parentis.sync.SyncCoordinator
import dev.schrammel.protego_parentis.sync.SyncGraph

/**
 * Sending extra time from a parent's phone (ST-4).
 *
 * Device-wide only. A parent phone holds no copy of the child's installed apps or
 * categories, so the per-app grants offered on the child's device cannot be
 * offered here without asking a parent to choose between package names.
 *
 * The grant is a message, not a local change: this phone has no budget to add to.
 * It arrives on the child's phone at the next poll, which is why the confirmation
 * says "sent" rather than "granted".
 */
class ParentBonusActivity : ParentRoleActivity() {

    private lateinit var sync: SyncCoordinator

    override fun onParentPhoneReady(savedInstanceState: Bundle?) {
        sync = SyncGraph.get(this)

        val column = contentColumn()
        column.addView(screenTitle(getString(R.string.parent_bonus_title)))

        if (!sync.settings().isUsable) {
            column.addView(bodyText(getString(R.string.parent_not_connected_hint)))
            showColumn(column)
            return
        }

        column.addView(bodyText(getString(R.string.parent_bonus_body)))
        column.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dimen(R.dimen.space_md) }

                bonusOptions().forEach { minutes ->
                    addView(
                        Button(context).apply {
                            text = getString(R.string.bonus_add_minutes, minutes)
                            layoutParams = LinearLayout.LayoutParams(
                                0,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                1f,
                            ).apply { marginEnd = dimen(R.dimen.space_xs) }
                            setOnClickListener { send(minutes) }
                        },
                    )
                }
            },
        )
        column.addView(captionText(getString(R.string.parent_bonus_note)))
        showColumn(column)
    }

    private fun send(minutes: Int) {
        sync.publishBonus(minutes)
        Audit.record(
            context = this,
            actor = AuditActor.PARENT,
            action = AuditAction.BONUS_GRANTED,
            subject = getString(R.string.requests_target_device),
            after = Audit.minutes(minutes),
        )
        toast(resources.getQuantityString(R.plurals.parent_bonus_sent, minutes, minutes))
        finish()
    }

    private fun bonusOptions(): List<Int> =
        resources.getIntArray(R.array.bonus_minute_options).toList()

    companion object {
        fun intent(context: Context) = Intent(context, ParentBonusActivity::class.java)
    }
}
