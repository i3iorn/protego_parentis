package dev.schrammel.protego_parentis.sync

import android.content.Context
import dev.schrammel.protego_parentis.security.LocalDeviceId
import dev.schrammel.protego_parentis.security.RoleStore
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.sync.core.StateOp
import java.time.Instant

/**
 * The shared wiring behind every one of [PolicyReplication]'s sibling
 * `write*`/`flatten*` pairs — [ScheduleReplication], [ContextRuleReplication],
 * [PlaceRefReplication], [AutoApprovalReplication], and [PolicyReplication]
 * itself.
 *
 * Each of those replicates a different record type with a different shape,
 * but all five converge on the same handful of operations once a record is
 * turned into paths: refuse an unresolved child or an unavailable identity,
 * write under this device's own role, and mark an id that used to be live and
 * no longer is with a tombstone rather than deleting its fields outright.
 * Held by composition rather than inheritance, the same way
 * [ChatReplication] and [RequestReplication] hold [StreamReplication] — a
 * shared *is-the-same-record-shape* base would force every future record
 * type through one supertype even where its shape genuinely differs (see
 * [PolicyReplication.flatten], which does not tombstone the way the other
 * four do).
 */
internal class PolicyRecordReplication(context: Context) {

    private val appContext = context.applicationContext
    val state = ReplicatedStateStore(appContext)

    /**
     * The shared wiring behind every `write*` method across the sibling
     * classes: refuse an unresolved child or an unavailable identity,
     * otherwise flatten and write under this device's own role. [flatten] is
     * the one thing that differs per record type — everything else here was
     * copy-pasted once per type before this existed.
     *
     * [flatten] reads current state (to decide what to tombstone) before
     * [ReplicatedStateStore.writeAll] ever runs — two separate operations
     * that, called from the UI thread with nothing else holding them
     * together, an inbound [SyncReceiver.apply] on the sync executor could
     * interleave between: a local edit computed from what has just gone
     * stale (#263). [ReplicatedStateStore.locked] makes the read and the
     * write one atomic step, the same guarantee [SyncKeying]'s executor
     * gives every other sync-state write, without moving this one off the
     * UI thread and onto it — several screens still rely on a local save
     * taking effect the instant this call returns.
     */
    fun write(childId: DeviceId?, flatten: (DeviceId) -> Map<String, String?>): List<StateOp> {
        val id = childId ?: return emptyList()
        val origin = LocalDeviceId.current() ?: return emptyList()
        val role = RoleStore(appContext).roleOrChild.toSyncRole()
        return state.locked { state.writeAll(flatten(id), origin, role) }
    }

    fun members(root: String): Set<String> = state.children(root).keys

    /**
     * Tombstones an id under [root] this replica already knows about that is
     * not in [liveIds] and does not already carry [deletedField] — the
     * shared tail every `flatten*` method in the sibling classes ends with,
     * once its own per-item fields are written. See
     * [ScheduleReplication.flattenSchedules] for why a marker rather than
     * nulling every field.
     */
    fun MutableMap<String, String?>.tombstoneGone(
        root: String,
        liveIds: Set<String>,
        deletedField: String,
        now: Instant,
    ) {
        state.idsUnder(root)
            .filterNot { it in liveIds }
            .filterNot { deletedField in state.children("$root/$it") }
            .forEach { gone -> put("$root/$gone/$deletedField", now.epochSecond.toString()) }
    }

    fun MutableMap<String, String?>.putMembers(root: String, members: Set<String>) =
        putValues(root, members.associateWith { PRESENT })

    fun MutableMap<String, String?>.putValues(root: String, values: Map<String, String>) {
        values.forEach { (key, value) -> put("$root/$key", value) }
        state.children(root).keys
            .filterNot { it in values }
            .forEach { gone -> put("$root/$gone", null) }
    }

    companion object {
        const val PRESENT = "1"
    }
}
