package dev.schrammel.dementor_watch.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import dev.schrammel.dementor_watch.R
import dev.schrammel.dementor_watch.audit.Audit
import dev.schrammel.dementor_watch.model.AuditAction
import dev.schrammel.dementor_watch.model.AuditActor
import dev.schrammel.dementor_watch.security.DeviceRole
import dev.schrammel.dementor_watch.security.RoleStore
import dev.schrammel.dementor_watch.sync.ParentPollWorker

/**
 * The first question the app asks: whose phone is this?
 *
 * One install, two jobs. The alternative was a second app sharing the sync layer,
 * the crypto and the design system by copy-paste, which is two things to keep in
 * step and two things to install.
 *
 * The answer is not a preference. It decides what the app collects, what it
 * enforces, and which side of a request this phone is on, so the screen explains
 * both options fully rather than offering a toggle labelled "parent mode".
 */
class RoleSetupActivity : DementorActivity() {

    private lateinit var roles: RoleStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        roles = RoleStore(this)

        val column = contentColumn()
        column.addView(screenTitle(getString(R.string.role_title)))
        column.addView(bodyText(getString(R.string.role_intro)))

        column.addView(sectionHeader(getString(R.string.role_child_header)))
        column.addView(
            card {
                addView(bodyText(getString(R.string.role_child_body)))
                addView(
                    Button(this@RoleSetupActivity).apply {
                        setText(R.string.role_child_choose)
                        setOnClickListener { choose(DeviceRole.CHILD) }
                    },
                )
            },
        )

        column.addView(sectionHeader(getString(R.string.role_parent_header)))
        column.addView(
            card {
                addView(bodyText(getString(R.string.role_parent_body)))
                addView(
                    Button(this@RoleSetupActivity).apply {
                        setText(R.string.role_parent_choose)
                        setOnClickListener { choose(DeviceRole.PARENT) }
                    },
                )
            },
        )

        column.addView(captionText(getString(R.string.role_change_later)))
        showColumn(column)
    }

    private fun choose(role: DeviceRole) {
        roles.choose(role)
        Audit.record(
            context = this,
            actor = AuditActor.PARENT,
            action = AuditAction.ROLE_CHANGED,
            after = role.name,
        )
        // A parent phone has no enforcement tick to fold polling into, so it
        // needs its own background check to hear about a request while closed.
        when (role) {
            DeviceRole.CHILD -> ParentPollWorker.cancel(this)
            DeviceRole.PARENT -> ParentPollWorker.schedule(this)
        }
        startActivity(
            when (role) {
                DeviceRole.CHILD -> Intent(this, HomeActivity::class.java)
                DeviceRole.PARENT -> ParentHomeActivity.intent(this)
            }.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }

    companion object {
        fun intent(context: Context) = Intent(context, RoleSetupActivity::class.java)
    }
}
