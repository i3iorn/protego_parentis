package dev.schrammel.protego_parentis.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.audit.Audit
import dev.schrammel.protego_parentis.model.AuditAction
import dev.schrammel.protego_parentis.model.AuditActor
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.RoleStore
import dev.schrammel.protego_parentis.sync.ParentPollWorker

/**
 * Turning a parent's phone into the managed one.
 *
 * Its own screen, and PIN-gated, because that is what it was before (#78). The
 * old parent settings screen was gated as a whole *for this one control* — see
 * the reasoning it carried: the role is the boundary the product rests on, and
 * a child who could flip a phone into parent mode could answer their own
 * requests, so changing it costs the PIN wherever it is offered.
 *
 * Splitting settings into an ungated list of rows would have quietly dropped
 * that gate had this stayed inline, which is why it moved here rather than onto
 * [SettingsActivity] with everything else.
 */
class RoleSwitchActivity : ParentGatedActivity() {

    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        column = contentColumn()
        showColumn(column)
    }

    override fun onGatedContentVisible() = render()

    override fun onGatedContentHidden() {
        column.removeAllViews()
    }

    private fun render() {
        column.removeAllViews()
        column.addView(screenTitle(getString(R.string.parent_settings_role_header)))
        column.addView(
            card {
                addView(captionText(getString(R.string.parent_settings_role_body)))
                addView(
                    actionButton().apply {
                        setText(R.string.parent_settings_become_child)
                        setOnClickListener { confirmSwitch() }
                    },
                )
            },
        )
    }

    /**
     * Switching to child mode turns this phone into the managed one. Nothing is
     * deleted, but the app starts collecting usage and enforcing rules here, which
     * is not a thing to do on a mistaken tap.
     */
    private fun confirmSwitch() {
        confirmDialog(
            R.string.parent_settings_become_child,
            R.string.parent_settings_become_child_confirm,
            R.string.parent_settings_become_child,
        ) {
            RoleStore(this).choose(DeviceRole.CHILD)
            ParentPollWorker.cancel(this)
            Audit.record(
                context = this,
                actor = AuditActor.PARENT,
                action = AuditAction.ROLE_CHANGED,
                before = DeviceRole.PARENT.name,
                after = DeviceRole.CHILD.name,
            )
            startActivity(
                Intent(this, HomeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            finish()
        }
    }

    companion object {
        fun intent(context: Context) = Intent(context, RoleSwitchActivity::class.java)
    }
}
