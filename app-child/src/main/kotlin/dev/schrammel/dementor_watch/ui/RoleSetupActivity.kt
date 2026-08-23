package dev.schrammel.dementor_watch.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import dev.schrammel.dementor_watch.R
import dev.schrammel.dementor_watch.audit.Audit
import dev.schrammel.dementor_watch.model.AuditAction
import dev.schrammel.dementor_watch.model.AuditActor
import dev.schrammel.dementor_watch.security.DeviceRole
import dev.schrammel.dementor_watch.security.PinLock
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
 *
 * Answering it also sets the PIN, and the role is not stored until that is done.
 * Previously the two were separate: a role was chosen, and the PIN was created
 * later by whoever first opened a settings screen. On the phone being limited,
 * that is a window in which the person being limited can set it — and a PIN they
 * chose protects nothing. Deferring the role until the PIN exists closes it, and
 * backing out of the PIN leaves the phone exactly as it was, with the question
 * still to answer.
 */
class RoleSetupActivity : DementorActivity() {

    private lateinit var roles: RoleStore

    /**
     * The answer being waited on, held across the PIN screen.
     *
     * Saved and restored because the PIN screen can outlive this activity if the
     * system reclaims it, and losing the choice would drop the parent back on a
     * question they had already answered.
     */
    private var pendingRole: DeviceRole? = null

    private val setPin = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val role = pendingRole
        pendingRole = null
        // Cancelled: nothing is stored, and the question stands. A role without a
        // PIN is the one outcome this flow exists to prevent.
        if (result.resultCode == RESULT_OK && role != null) commit(role)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingRole?.let { outState.putString(STATE_PENDING_ROLE, it.name) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        roles = RoleStore(this)
        pendingRole = savedInstanceState?.getString(STATE_PENDING_ROLE)
            ?.let { stored -> DeviceRole.entries.firstOrNull { it.name == stored } }

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

        column.addView(captionText(getString(R.string.role_pin_next)))
        column.addView(captionText(getString(R.string.role_change_later)))
        showColumn(column)
    }

    /**
     * Takes the answer, but stores nothing until there is a PIN to protect it.
     */
    private fun choose(role: DeviceRole) {
        if (PinLock(this).isSet) {
            commit(role)
            return
        }
        pendingRole = role
        setPin.launch(PinActivity.intent(this))
    }

    private fun commit(role: DeviceRole) {
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
        private const val STATE_PENDING_ROLE = "pendingRole"

        fun intent(context: Context) = Intent(context, RoleSetupActivity::class.java)
    }
}
