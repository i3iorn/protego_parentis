package dev.schrammel.dementor_watch.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import dev.schrammel.dementor_watch.R
import dev.schrammel.dementor_watch.audit.Audit
import dev.schrammel.dementor_watch.model.AuditAction
import dev.schrammel.dementor_watch.model.AuditActor
import dev.schrammel.dementor_watch.sync.DeviceId
import dev.schrammel.dementor_watch.sync.KnownDevice
import dev.schrammel.dementor_watch.sync.PendingDevice
import dev.schrammel.dementor_watch.sync.RosterStore
import dev.schrammel.dementor_watch.sync.SyncConfigStore
import dev.schrammel.dementor_watch.sync.SyncCoordinator
import dev.schrammel.dementor_watch.sync.SyncRole

private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

/**
 * Who is in the family, and which phone is which.
 *
 * This screen is the whole point of per-device signing. Encryption proves a
 * message came from someone with the family passphrase; the roster is where a
 * person decides *which* someone. Enrolment is manual and needs a fingerprint
 * compared against the other phone's screen, because the attacker this defends
 * against lives in the house and can wait for a convenient moment to announce.
 *
 * PIN-gated. On the child's device that is obvious. On a parent's phone it
 * matters just as much: approving a device is the act that lets it start
 * answering requests.
 */
class FamilyDevicesActivity : ParentGatedActivity() {

    private lateinit var roster: RosterStore
    private lateinit var config: SyncConfigStore
    private lateinit var sync: SyncCoordinator
    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        roster = RosterStore(this)
        config = SyncConfigStore(this)
        sync = SyncCoordinator(this)
        column = contentColumn()
        showColumn(column)
    }

    override fun onGatedContentVisible() {
        // Announcing on arrival is what makes the other phone's list fill in while
        // someone is standing in front of it, rather than up to an hour later.
        sync.announceSelf(force = true)
        sync.pollNow { runOnUiThread { if (!isFinishing && !isDestroyed) render() } }
        render()
    }

    override fun onGatedContentHidden() {
        column.removeAllViews()
    }

    private fun render() {
        column.removeAllViews()
        column.addView(screenTitle(getString(R.string.devices_title)))
        column.addView(bodyText(getString(R.string.devices_intro)))

        column.addView(sectionHeader(getString(R.string.devices_this_phone)))
        column.addView(thisPhone())

        val pending = roster.pending
        if (pending.isNotEmpty()) {
            column.addView(sectionHeader(getString(R.string.devices_waiting)))
            column.addView(captionText(getString(R.string.devices_waiting_hint)))
            pending.forEach { column.addView(pendingCard(it)) }
        }

        val refused = roster.refused
        if (refused.isNotEmpty()) {
            column.addView(sectionHeader(getString(R.string.devices_refused)))
            column.addView(captionText(getString(R.string.devices_refused_hint)))
            column.addView(group(refused.sorted().map(::refusedRow)))
        }

        column.addView(sectionHeader(getString(R.string.devices_trusted)))
        val known = roster.devices.sortedBy { it.name.lowercase() }
        if (known.isEmpty()) {
            column.addView(bodyText(getString(R.string.devices_none)))
        } else {
            column.addView(group(known.map(::knownRow)))
        }
    }

    private fun thisPhone(): LinearLayout = card {
        val settings = config.load()
        addView(
            TextView(this@FamilyDevicesActivity).apply {
                text = settings.deviceName
                styleAs(R.style.TextAppearance_Dementor_Subtitle)
            },
        )
        addView(
            statRow(
                getString(R.string.devices_fingerprint),
                settings.deviceId.ifBlank { getString(R.string.devices_no_identity) },
            ),
        )
        addView(captionText(getString(R.string.devices_fingerprint_hint)))
        addView(
            Button(this@FamilyDevicesActivity).apply {
                setText(R.string.devices_rename_this)
                setOnClickListener { renameSelf(settings.deviceName) }
            },
        )
    }

    /**
     * A device asking to join, with the two facts needed to decide: what it calls
     * itself, and the fingerprint to check against its own screen.
     */
    private fun pendingCard(device: PendingDevice): LinearLayout = card {
        addView(
            TextView(this@FamilyDevicesActivity).apply {
                text = device.name.ifBlank { getString(R.string.devices_unnamed) }
                styleAs(R.style.TextAppearance_Dementor_Subtitle)
            },
        )
        addView(statRow(getString(R.string.devices_role), roleName(device.role)))
        addView(statRow(getString(R.string.devices_fingerprint), device.id.value))
        addView(
            LinearLayout(this@FamilyDevicesActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    topMargin = dimen(R.dimen.space_sm)
                }
                addView(
                    Button(context).apply {
                        setText(R.string.devices_approve)
                        layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                            marginEnd = dimen(R.dimen.space_xs)
                        }
                        setOnClickListener { confirmApprove(device) }
                    },
                )
                addView(
                    Button(context).apply {
                        setText(R.string.devices_reject)
                        layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                        setOnClickListener {
                            roster.reject(device.id)
                            render()
                        }
                    },
                )
            },
        )
    }

    /**
     * A device the family turned away, and the way back.
     *
     * Rejecting is now permanent until undone, which is the point — but the
     * commonest stranger in that list is a family member who reinstalled, and a
     * mistake there must not be a phone that can never rejoin.
     */
    private fun refusedRow(id: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        setPadding(0, dimen(R.dimen.space_sm), 0, dimen(R.dimen.space_sm))

        addView(
            TextView(context).apply {
                text = id
                styleAs(R.style.TextAppearance_Dementor_ListItem)
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            },
        )
        addView(
            Button(context).apply {
                setText(R.string.devices_let_ask_again)
                setOnClickListener {
                    roster.unrefuse(FamilyDevicesActivity.idOf(id))
                    render()
                }
            },
        )
    }

    private fun knownRow(device: KnownDevice): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        setPadding(0, dimen(R.dimen.space_sm), 0, dimen(R.dimen.space_sm))
        isClickable = true
        setOnClickListener { manage(device) }

        addView(
            TextView(context).apply {
                text = device.name
                styleAs(R.style.TextAppearance_Dementor_ListItem)
            },
        )
        addView(
            TextView(context).apply {
                text = getString(
                    R.string.devices_row_detail,
                    roleName(device.role),
                    device.id.value,
                )
                styleAs(R.style.TextAppearance_Dementor_Caption)
            },
        )
    }

    /**
     * Approving is a two-step because it is the security decision on this screen.
     * The confirmation restates the fingerprint, so the last thing seen before
     * tapping yes is the thing that was supposed to be checked.
     */
    private fun confirmApprove(device: PendingDevice) {
        val name = EditText(this).apply {
            hint = getString(R.string.devices_name_hint)
            setText(device.name)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dimen(R.dimen.screen_padding)
            setPadding(pad, pad, pad, 0)
            addView(
                captionText(
                    getString(
                        R.string.devices_approve_body,
                        device.id.value,
                        roleName(device.role),
                    ),
                ),
            )
            addView(name)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.devices_approve)
            .setView(content)
            .setPositiveButton(R.string.devices_approve) { _, _ ->
                val approved = roster.approve(device.id, name.text.toString())
                if (approved != null) {
                    Audit.record(
                        context = this,
                        actor = AuditActor.PARENT,
                        action = AuditAction.DEVICE_APPROVED,
                        subject = approved.name,
                        after = approved.role.name,
                    )
                }
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun manage(device: KnownDevice) {
        AlertDialog.Builder(this)
            .setTitle(device.name)
            .setMessage(
                getString(
                    R.string.devices_manage_body,
                    device.id.value,
                    roleName(device.role),
                ),
            )
            .setPositiveButton(R.string.devices_rename) { _, _ -> rename(device) }
            .setNegativeButton(R.string.devices_revoke) { _, _ -> confirmRevoke(device) }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    private fun rename(device: KnownDevice) {
        promptForName(device.name) { chosen ->
            roster.rename(device.id, chosen)
            render()
        }
    }

    private fun renameSelf(current: String) {
        promptForName(current) { chosen ->
            config.deviceName = chosen
            // The other phones only learn the new name from an announcement.
            sync.announceSelf(force = true)
            Toast.makeText(this, R.string.devices_renamed, Toast.LENGTH_SHORT).show()
            render()
        }
    }

    private fun promptForName(current: String, onChosen: (String) -> Unit) {
        val input = EditText(this).apply {
            hint = getString(R.string.devices_name_hint)
            setText(current)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dimen(R.dimen.screen_padding)
            setPadding(pad, pad, pad, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.devices_rename)
            .setView(content)
            .setPositiveButton(R.string.devices_rename) { _, _ ->
                val chosen = input.text.toString().trim()
                if (chosen.isNotEmpty()) onChosen(chosen)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Revoking is confirmed because it is silent when it goes wrong: the revoked
     * phone carries on publishing and nothing acts on any of it.
     */
    private fun confirmRevoke(device: KnownDevice) {
        AlertDialog.Builder(this)
            .setTitle(R.string.devices_revoke)
            .setMessage(getString(R.string.devices_revoke_body, device.name))
            .setPositiveButton(R.string.devices_revoke) { _, _ ->
                roster.revoke(device.id)
                Audit.record(
                    context = this,
                    actor = AuditActor.PARENT,
                    action = AuditAction.DEVICE_REVOKED,
                    subject = device.name,
                )
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun roleName(role: SyncRole): String = getString(
        when (role) {
            SyncRole.CHILD -> R.string.devices_role_child
            SyncRole.PARENT -> R.string.devices_role_parent
        },
    )

    companion object {
        fun intent(context: Context) = Intent(context, FamilyDevicesActivity::class.java)

        private fun idOf(value: String) = DeviceId(value)
    }
}
