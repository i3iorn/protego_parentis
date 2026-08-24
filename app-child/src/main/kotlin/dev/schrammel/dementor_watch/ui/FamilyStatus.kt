package dev.schrammel.dementor_watch.ui

import android.content.Context
import dev.schrammel.dementor_watch.security.DeviceRole
import dev.schrammel.dementor_watch.security.RoleStore
import dev.schrammel.dementor_watch.sync.Enrolment
import dev.schrammel.dementor_watch.sync.RosterStore
import dev.schrammel.dementor_watch.sync.SyncConfigStore
import dev.schrammel.dementor_watch.sync.SyncRole

/** Where this phone has got to in joining a family. */
enum class FamilyState {
    /** No family configured. This phone works alone.  */
    ALONE,

    /** Another phone is waiting for somebody here to approve it. */
    SOMEONE_WAITING,

    /** Connected, but no other phone has been approved yet, in either direction. */
    NOT_YET_PAIRED,

    /** Connected and working. */
    READY,
}

data class FamilySnapshot(val state: FamilyState, val waiting: Int)

/**
 * The one thing about joining a family that the app used to keep to itself.
 *
 * Joining is deliberately not the end of it: a device that knows the code and the
 * passphrase reaches the topic and is then a stranger in a queue until somebody
 * approves it on another phone. That is the whole point of the roster, and it is
 * the reason knowing the passphrase is not enough to answer a child's requests.
 *
 * But the screens never said so. Both phones would join, both would show a
 * perfectly normal "This family" page, and then nothing would happen — no
 * requests, no answers, no summaries — with no indication anywhere that a step was
 * outstanding. The only place the pending list appeared was a settings screen
 * nobody had been sent to. From the outside that is indistinguishable from the
 * sync being broken, and the natural response is to leave the family and try
 * again, which produces exactly the same silence.
 *
 * So this reads the state once, both home screens show it, and the wording says
 * the thing that is easy to get wrong: **both phones have to do it.** Approving
 * on one is half the job.
 *
 * Reads preference files, so call it off the main thread.
 */
object FamilyStatus {

    fun read(context: Context): FamilySnapshot {
        if (!SyncConfigStore(context).load().isUsable) {
            return FamilySnapshot(FamilyState.ALONE, 0)
        }

        val roster = RosterStore(context)
        val role = when (RoleStore(context).roleOrChild) {
            DeviceRole.CHILD -> SyncRole.CHILD
            DeviceRole.PARENT -> SyncRole.PARENT
        }

        // Asked the same way the devices screen asks it. A child's phone that
        // already trusts a parent has no approve button, so telling it that
        // something is waiting would point at a screen offering nothing to do.
        val mayApprove = Enrolment.mayApprove(role, roster.parents().size)
        val waiting = if (mayApprove) roster.pending.size else 0

        return when {
            waiting > 0 -> FamilySnapshot(FamilyState.SOMEONE_WAITING, waiting)
            roster.devices.isEmpty() -> FamilySnapshot(FamilyState.NOT_YET_PAIRED, 0)
            else -> FamilySnapshot(FamilyState.READY, 0)
        }
    }
}
