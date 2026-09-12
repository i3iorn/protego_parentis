package dev.schrammel.protego_parentis.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.audit.Audit
import dev.schrammel.protego_parentis.model.AuditAction
import dev.schrammel.protego_parentis.model.AuditActor
import dev.schrammel.protego_parentis.sync.ConsensusKind
import dev.schrammel.protego_parentis.sync.ConsensusReplication
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.sync.core.KnownDevice
import dev.schrammel.protego_parentis.security.LocalDeviceId
import dev.schrammel.protego_parentis.sync.appVersionName
import dev.schrammel.protego_parentis.sync.versionsDiffer
import dev.schrammel.protego_parentis.sync.core.PendingDevice
import dev.schrammel.protego_parentis.sync.RosterStore
import dev.schrammel.protego_parentis.sync.SyncConfigStore
import dev.schrammel.protego_parentis.sync.SyncCoordinator
import dev.schrammel.protego_parentis.sync.core.Enrolment
import dev.schrammel.protego_parentis.sync.core.SyncRole
import dev.schrammel.protego_parentis.sync.toSyncRole
import dev.schrammel.protego_parentis.security.RoleStore

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
 *
 * **Who may approve.** Parents. Not the child's phone — if the phone under
 * management could admit a parent device, whoever holds that phone could admit one
 * of their own and answer their own requests, and the roster would be protecting
 * nothing. A parent approving here tells the rest of the family, and every phone
 * that already trusts them enrols the newcomer without anybody touching it.
 *
 * The single exception is the founding parent, who cannot be vouched for because
 * there is nobody yet to do the vouching. That one enrolment happens here, on the
 * child's phone, behind the PIN, by whoever is setting it up — and once it has a
 * parent this screen never offers to approve again.
 *
 * A pending device claiming to be a second managed phone is a different story
 * (#45): tapping Approve there never admits it on this phone's own say-so.
 * It raises (or joins) a [ConsensusKind.ENROL_CHILD] proposal instead, which
 * only takes effect once every current parent has independently done the
 * same — see that proposal kind's own doc comment for why one parent's
 * approval alone was never enough to admit a device that receives every
 * rule and every position.
 */
class FamilyDevicesActivity : ParentGatedActivity() {

    private lateinit var roster: RosterStore
    private lateinit var config: SyncConfigStore
    private lateinit var sync: SyncCoordinator
    private lateinit var consensus: ConsensusReplication
    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        roster = RosterStore(this)
        config = SyncConfigStore(this)
        sync = SyncCoordinator(this)
        consensus = ConsensusReplication(this)
        column = contentColumn()
        showColumn(column)
    }

    override fun onGatedContentVisible() {
        // Announcing on arrival is what makes the other phone's list fill in while
        // someone is standing in front of it, rather than up to an hour later.
        sync.announceSelf(force = true)
        // Same reasoning, for the rest of the family: a newly-joined parent phone
        // trusts nobody until it has approved one existing device by hand, and
        // only then can it act on a vouch at all. Repeating every vouch this
        // phone can make, every time this screen opens, is what lets that phone
        // pick up everyone else without a coincidence of timing deciding whether
        // it has to approve them one by one too.
        revouchKnownParents()
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

        val mayApprove = Enrolment.mayApprove(role(), roster.parents().size)

        if (!mayApprove) {
            // Said plainly rather than by an absent button: a screen that lists
            // phones waiting and offers nothing looks broken.
            column.addView(sectionHeader(getString(R.string.devices_waiting)))
            column.addView(bodyText(getString(R.string.devices_parents_decide)))
        }

        val pending = if (mayApprove) roster.pending else emptyList()
        if (pending.isNotEmpty()) {
            column.addView(sectionHeader(getString(R.string.devices_waiting)))
            column.addView(captionText(getString(R.string.devices_waiting_hint)))
            addCollapsibleRows(column, pending.map(::pendingCard))
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
            // Said once above the list rather than repeated on every row: the
            // interesting fact is that the family has drifted apart, not which
            // particular phone is the odd one out.
            if (known.any { versionsDiffer(myVersion, it.appVersion) }) {
                column.addView(captionText(getString(R.string.devices_version_mixed, myVersion)))
            }
            column.addView(group(known.map(::knownRow)))
        }

        // The one screen behind Settings that people reach for directly, on its
        // own row rather than a tap further inside Settings — pairing a new
        // phone or fixing a mistyped relay is usually the reason someone opened
        // this screen at all, not a detour from looking at who's in the family.
        addSection(
            column,
            null,
            listOf(
                navigationRow(
                    getString(R.string.parent_row_sync),
                    getString(R.string.parent_row_sync_detail),
                ) { startActivity(JoinFamilyActivity.intent(this)) },
            ),
        )

        // Settings used to hang off this screen, on the reasoning that they are
        // things you reach because of the family you are looking at. They have
        // their own row on the dashboard now (#78) — this was the only way in,
        // and a setting nobody can find is not protected by being tidy.
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
     *
     * A CHILD-claimed candidate this device has already approved (raised or
     * joined its [ConsensusKind.ENROL_CHILD] proposal) shows a waiting state
     * instead of the Approve/Reject row — there is nothing further for this
     * parent to decide until the rest of the family catches up.
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

        val selfId = LocalDeviceId.current()?.let(::DeviceId)
        val alreadyApprovedByThisDevice = device.role == SyncRole.CHILD && selfId != null &&
            consensus.proposals().any {
                it.kind == ConsensusKind.ENROL_CHILD && it.target == device.id && selfId in it.approvals
            }

        if (alreadyApprovedByThisDevice) {
            addView(captionText(getString(R.string.devices_awaiting_family)))
        } else {
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
        // Only when it differs. A version that matches is not news, and one we
        // have never been told is not a mismatch.
        if (versionsDiffer(myVersion, device.appVersion)) {
            addView(
                TextView(context).apply {
                    text = getString(R.string.devices_version_other, device.appVersion)
                    styleAs(R.style.TextAppearance_Dementor_Caption)
                    setTextColor(ContextCompat.getColor(context, R.color.state_warning))
                },
            )
        }
    }

    /** Read once per render; it cannot change while the screen is open. */
    private val myVersion: String by lazy { appVersionName(this) }

    /**
     * Approving is a two-step because it is the security decision on this screen.
     * The confirmation restates the fingerprint, so the last thing seen before
     * tapping yes is the thing that was supposed to be checked.
     *
     * A CHILD candidate never takes this path to admission — see
     * [confirmEnrolChild].
     */
    private fun confirmApprove(device: PendingDevice) {
        if (device.role == SyncRole.CHILD) {
            confirmEnrolChild(device)
            return
        }

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
                    // Tell the family, so nobody else has to do this again —
                    // this is what makes one parent's approval enough. Vouching
                    // for every parent this phone already knows, not only the
                    // one just approved, is what lets a freshly-joined parent
                    // pick up the rest of the family the moment it trusts even
                    // one of them, rather than approving each by hand.
                    revouchKnownParents()
                }
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * A CHILD candidate's "Approve" button, kept apart from [confirmApprove]
     * because it can never do what that one does. This never calls
     * [RosterStore.approve] directly and never offers to rename the
     * candidate — a name typed here would only ever apply to this device's
     * own roster entry, since each parent independently enrols the candidate
     * from its own locally-observed [PendingDevice.name] once every current
     * parent has done the same (see [ConsensusKind.ENROL_CHILD]), and a
     * field that looked editable but did not actually rename the device
     * everywhere would be worse than no field at all.
     *
     * Tapping Approve either raises a new [ConsensusKind.ENROL_CHILD]
     * proposal naming this candidate, or — if another parent already raised
     * one — adds this parent's own approval to it. Either way this device's
     * own eventual enrolment of the candidate happens later, once every
     * current parent has independently reached the same point; nothing here
     * admits it on the spot.
     */
    private fun confirmEnrolChild(device: PendingDevice) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dimen(R.dimen.screen_padding)
            setPadding(pad, pad, pad, 0)
            addView(captionText(getString(R.string.devices_enrol_child_body, device.id.value)))
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.devices_approve)
            .setView(content)
            .setPositiveButton(R.string.devices_approve) { _, _ ->
                val existing = consensus.proposals().firstOrNull {
                    it.kind == ConsensusKind.ENROL_CHILD && it.target == device.id
                }
                val proposalId = existing?.id ?: consensus.propose(ConsensusKind.ENROL_CHILD, target = device.id)
                if (proposalId != null) {
                    consensus.approve(proposalId)
                    Audit.record(
                        context = this,
                        actor = AuditActor.PARENT,
                        action = AuditAction.DEVICE_APPROVED,
                        subject = device.name.ifBlank { device.id.value },
                        after = device.role.name,
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
        promptForText(R.string.devices_rename, R.string.devices_name_hint, prefill = device.name) { chosen ->
            roster.rename(device.id, chosen)
            render()
        }
    }

    private fun renameSelf(current: String) {
        promptForText(R.string.devices_rename, R.string.devices_name_hint, prefill = current) { chosen ->
            config.deviceName = chosen
            // The other phones only learn the new name from an announcement.
            sync.announceSelf(force = true)
            toast(R.string.devices_renamed)
            render()
        }
    }

    /**
     * Revoking is confirmed because it is silent when it goes wrong: the revoked
     * phone carries on publishing and nothing acts on any of it.
     *
     * Only a parent device's removal is told to the rest of the family — see
     * [Enrolment.shouldAcceptRevoke]. The child device is never revoked this
     * way at all: removing it is the local, PIN-gated action already reachable
     * from its own phone, not something one parent's tap can broadcast.
     */
    private fun confirmRevoke(device: KnownDevice) {
        confirmDialog(
            R.string.devices_revoke,
            getString(R.string.devices_revoke_body, device.name),
            R.string.devices_revoke,
        ) {
            roster.revoke(device.id)
            Audit.record(
                context = this,
                actor = AuditActor.PARENT,
                action = AuditAction.DEVICE_REVOKED,
                subject = device.name,
            )
            if (Enrolment.shouldAcceptRevoke(device.role)) {
                sync.publishRevoke(device)
            }
            render()
        }
    }

    private fun role(): SyncRole = RoleStore(this).roleOrChild.toSyncRole()

    /**
     * Restates, to the family, every parent this phone already trusts.
     *
     * A vouch for a device the receiver already has is a no-op — [RosterStore.enrol]
     * skips anything already in the known list — so repeating the whole set costs
     * nothing on a phone that has heard it before. What it buys is for a phone that
     * has not: a newly-joined parent trusts nobody until it has compared a
     * fingerprint and approved one existing device by hand, and only from that
     * moment can it act on a vouch at all. There is no message telling this phone
     * *when* that happens on the other end, so instead every vouch this phone can
     * make is repeated on every approval and every time this screen is opened —
     * one of those two will land after the newcomer's one manual approval, without
     * either phone having to time it.
     *
     * Never the child: a vouch is refused for anything but a parent role, by
     * design — see [Enrolment.shouldAccept] for why extending the same shortcut
     * to the managed phone would be a real hole rather than a convenience.
     */
    private fun revouchKnownParents() {
        if (role() != SyncRole.PARENT) return
        roster.parents().forEach(sync::publishVouch)
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
