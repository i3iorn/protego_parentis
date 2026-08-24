package dev.schrammel.dementor_watch.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import dev.schrammel.dementor_watch.R
import dev.schrammel.dementor_watch.audit.Audit
import dev.schrammel.dementor_watch.enforcement.EnforcementService
import dev.schrammel.dementor_watch.model.AuditAction
import dev.schrammel.dementor_watch.model.AuditActor
import dev.schrammel.dementor_watch.rules.SosStore
import dev.schrammel.dementor_watch.sync.SyncCoordinator

/**
 * The child's emergency button (LOC-3).
 *
 * Not gated by anything, and reachable from the dashboard, which is the whole
 * point: it has to work while every app on the phone is suspended and the daily
 * limit is spent. An emergency feature behind a limit is not one.
 *
 * Only the child can raise it and only the child can end it. A parent who could
 * cancel it would be able to decide, from somewhere else, that the situation their
 * child is in has finished.
 *
 * The confirmation says plainly that this is not a substitute for calling
 * emergency services. Parent phones poll every few minutes, so the honest
 * description of this feature is "tells your family", not "gets help" — and a
 * child in danger should hear that before they rely on it.
 */
class SosActivity : DementorActivity() {

    private lateinit var sos: SosStore
    private lateinit var sync: SyncCoordinator
    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sos = SosStore(this)
        sync = SyncCoordinator(this)
        column = contentColumn()
        showColumn(column)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        column.removeAllViews()
        column.addView(screenTitle(getString(R.string.sos_button)))

        if (sos.isActive) {
            column.addView(
                card {
                    addView(
                        bodyText(getString(R.string.sos_active))
                            .styleAs(R.style.TextAppearance_Dementor_Subtitle),
                    )
                    addView(captionText(getString(R.string.sos_active_body)))
                },
            )
            column.addView(
                actionButton().apply {
                    setText(R.string.sos_clear)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dimen(R.dimen.space_lg) }
                    setOnClickListener { clear() }
                },
            )
            return
        }

        column.addView(bodyText(getString(R.string.sos_confirm_body)))
        column.addView(captionText(getString(R.string.sos_delay_warning)))
        column.addView(
            actionButton().apply {
                setText(R.string.sos_send)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dimen(R.dimen.space_lg) }
                setOnClickListener { confirm() }
            },
        )
    }

    /**
     * Confirmed, because the consequence is every adult in the family being told
     * at once — but with one tap on this screen, not a sequence. A button that is
     * hard to press by accident must still be easy to press on purpose.
     */
    private fun confirm() {
        AlertDialog.Builder(this)
            .setTitle(R.string.sos_confirm_title)
            .setMessage(R.string.sos_confirm_body)
            .setPositiveButton(R.string.sos_send) { _, _ -> raise() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun raise() {
        if (!sos.raise()) return

        Audit.record(this, AuditActor.CHILD, AuditAction.SOS_RAISED)
        sync.publishSosRaised()
        // Straight away, so every restriction lifts now rather than at the next
        // tick — the point of the feature is the phone working immediately.
        EnforcementService.start(this)
        render()
    }

    private fun clear() {
        sos.clear()
        Audit.record(this, AuditActor.CHILD, AuditAction.SOS_CLEARED)
        sync.publishSosCleared()
        EnforcementService.start(this)
        render()
    }

    companion object {
        fun intent(context: Context) = Intent(context, SosActivity::class.java)
    }
}
