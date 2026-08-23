package dev.schrammel.dementor_watch.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import dev.schrammel.dementor_watch.R
import dev.schrammel.dementor_watch.audit.Audit
import dev.schrammel.dementor_watch.model.AuditAction
import dev.schrammel.dementor_watch.model.AuditActor
import dev.schrammel.dementor_watch.security.DeviceRole
import dev.schrammel.dementor_watch.security.RoleStore
import dev.schrammel.dementor_watch.sync.ParentPollWorker

/**
 * The settings a parent phone has, which is very nearly none.
 *
 * Its one real job is the role switch, and that is why the screen is PIN-gated
 * while the rest of parent mode is not. The role is the boundary the product rests
 * on: a child who could flip a phone into parent mode could answer their own
 * requests, so changing it costs the PIN wherever it is offered.
 */
class ParentSettingsActivity : ParentGatedActivity() {

    private lateinit var roles: RoleStore
    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        roles = RoleStore(this)
        column = contentColumn()
        showColumn(column)
    }

    override fun onGatedContentVisible() = render()

    override fun onGatedContentHidden() {
        column.removeAllViews()
    }

    private fun render() {
        column.removeAllViews()
        column.addView(screenTitle(getString(R.string.parent_settings_title)))

        column.addView(sectionHeader(getString(R.string.parent_settings_pin_header)))
        column.addView(bodyText(getString(R.string.parent_settings_pin_body)))
        column.addView(
            Button(this).apply {
                setText(R.string.rules_change_pin)
                setOnClickListener { startActivity(PinActivity.intent(context, change = true)) }
            },
        )

        column.addView(sectionHeader(getString(R.string.biometric_header)))
        column.addView(biometricSetting { render() })

        column.addView(sectionHeader(getString(R.string.parent_settings_role_header)))
        column.addView(bodyText(getString(R.string.parent_settings_role_body)))
        column.addView(
            Button(this).apply {
                setText(R.string.parent_settings_become_child)
                setOnClickListener { confirmSwitch() }
            },
        )
    }

    /**
     * Switching to child mode turns this phone into the managed one. Nothing is
     * deleted, but the app starts collecting usage and enforcing rules here, which
     * is not a thing to do on a mistaken tap.
     */
    private fun confirmSwitch() {
        AlertDialog.Builder(this)
            .setTitle(R.string.parent_settings_become_child)
            .setMessage(R.string.parent_settings_become_child_confirm)
            .setPositiveButton(R.string.parent_settings_become_child) { _, _ ->
                roles.choose(DeviceRole.CHILD)
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
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        fun intent(context: Context) = Intent(context, ParentSettingsActivity::class.java)
    }
}
