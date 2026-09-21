package dev.schrammel.protego_parentis.sync

import android.content.Context
import dev.schrammel.protego_parentis.model.ScheduleBlock
import dev.schrammel.protego_parentis.rules.ScheduleStore
import dev.schrammel.protego_parentis.rules.StoredSchedule
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.sync.core.ReplicationRules
import dev.schrammel.protego_parentis.sync.core.StateOp
import java.time.Instant

/**
 * Named schedule windows, as replicated state — the bridge to
 * [ScheduleStore] that [PolicyReplication] delegates to.
 *
 * Deliberately not folded into [PolicyReplication.write]/[PolicyReplication.read]:
 * [dev.schrammel.protego_parentis.rules.PolicySnapshot] does not carry
 * schedules, and giving it a `schedules` field would mean every caller that
 * builds or applies one — including the whole-policy
 * `POLICY_SNAPSHOT`/`POLICY_UPDATED` backward-compatibility path — takes on a
 * field it may not need.
 */
internal class ScheduleReplication(context: Context) {

    private val appContext = context.applicationContext
    private val store = ScheduleStore(appContext)
    private val records = PolicyRecordReplication(appContext)
    private val state get() = records.state

    /** Records [schedules] as [childId]'s replicated named windows. */
    fun writeSchedules(
        schedules: List<StoredSchedule>,
        childId: DeviceId?,
        now: Instant = Instant.now(),
    ): List<StateOp> = records.write(childId) { id -> flattenSchedules(schedules, id, now) }

    /**
     * [childId]'s replicated schedules — every one this replica holds that is
     * both still live and complete enough for [dev.schrammel.protego_parentis.enforcement]
     * to act on.
     *
     * A schedule this replica has been told is gone (see [FIELD_SCHEDULE_DELETED]
     * on [flattenSchedules]) is excluded outright, regardless of what its other
     * fields say — a stale `name` or `start` a concurrent edit left behind is
     * simply never looked at once that flag is present. A schedule that is not
     * (yet) gone but has not fully arrived — a `name` with no `days` yet, say —
     * is excluded by [StoredSchedule.isComplete] the same way an editor refuses
     * to save it: the one completeness check now does both jobs.
     */
    fun readSchedules(childId: DeviceId?): List<StoredSchedule> {
        val id = childId ?: return emptyList()
        val root = "${ReplicationRules.POLICY}/${id.value}/$PATH_SCHEDULES"
        return state.idsUnder(root).mapNotNull { scheduleId ->
            val scheduleRoot = "$root/$scheduleId"
            val fields = state.children(scheduleRoot)
            if (FIELD_SCHEDULE_DELETED in fields) return@mapNotNull null

            StoredSchedule(
                id = scheduleId,
                name = fields[FIELD_SCHEDULE_NAME] ?: return@mapNotNull null,
                days = records.members("$scheduleRoot/$FIELD_SCHEDULE_DAYS").mapNotNull(String::toIntOrNull).toSet(),
                startMinuteOfDay = fields[FIELD_SCHEDULE_START]?.toIntOrNull() ?: return@mapNotNull null,
                endMinuteOfDay = fields[FIELD_SCHEDULE_END]?.toIntOrNull() ?: return@mapNotNull null,
                packages = records.members("$scheduleRoot/$FIELD_SCHEDULE_PACKAGES"),
                mode = ScheduleBlock.Mode.entries.firstOrNull { it.name == fields[FIELD_SCHEDULE_MODE] }
                    ?: ScheduleBlock.Mode.EXCLUSIVE,
                enabled = fields[FIELD_SCHEDULE_ENABLED]?.toBooleanStrictOrNull() ?: true,
                kind = ScheduleBlock.Kind.entries.firstOrNull { it.name == fields[FIELD_SCHEDULE_KIND] }
                    ?: ScheduleBlock.Kind.CUSTOM,
                parityAnchorEpochDay = fields[FIELD_SCHEDULE_PARITY_ANCHOR]?.toLongOrNull(),
            ).takeIf { it.isComplete }
        }
    }

    /**
     * Records this phone's own [ScheduleStore] as replicated writes, the
     * schedule equivalent of [PolicyReplication.pushFromLocalPolicy].
     *
     * Called on the managed phone on every poll, same as the rest of policy.
     * No version gate: unlike [PolicyReplication.pushFromLocalPolicy],
     * [ScheduleStore] carries no counter of its own to check first, and
     * [writeSchedules] already costs nothing when nothing changed —
     * [ReplicatedStateStore.writeAll] diffs against what is already held
     * before queuing anything.
     */
    fun pushSchedulesFromLocal(childId: DeviceId?): List<StateOp> =
        writeSchedules(store.schedules, childId)

    /**
     * Writes this replica's schedules back onto [ScheduleStore], the
     * schedule equivalent of [PolicyReplication.pullIntoLocalPolicy].
     *
     * Only ever meaningful on the managed phone — a parent's phone has no
     * local [ScheduleStore] anything enforces. Called only alongside a
     * POLICY change this replica just received, the same trigger
     * [PolicyReplication.pullIntoLocalPolicy] uses, and with the same
     * characteristic: a schedule whose fields are still arriving is read as
     * gone or unfinished until the rest catches up, exactly as a policy
     * field briefly reads as its default during the same window.
     */
    fun pullSchedulesIntoLocal(childId: DeviceId?) {
        store.schedules = readSchedules(childId)
    }

    /**
     * Every schedule as a path, plus a [FIELD_SCHEDULE_DELETED] marker for one
     * that used to be live and no longer is.
     *
     * This is the one place policy replication departs from how
     * [PolicyReplication.flatten] deletes a category: a gone category has
     * every one of its paths written to a tombstone, and that is exactly the
     * shape a genuine race can break. A delete and a concurrent rename land
     * on the *same* schedule from two different devices; each writes a
     * different path (`deleted` versus `name`), so [dev.schrammel.protego_parentis.sync.core.StateMerge]
     * never compares them against each other at all — there is no shared
     * Lamport stamp for "delete" to win or lose against "rename", and the
     * two fields simply resolve independently. If the delete instead nulled
     * `name` itself, whichever write happened to carry the higher stamp
     * would decide the outcome, and a replica that saw the rename's stamp
     * win would go on showing a schedule that every other replica had
     * already dropped.
     *
     * Writing a single `deleted` marker sidesteps that: [readSchedules]
     * excludes a schedule the instant that marker is present, with nothing
     * left to race. A rename arriving after the marker changes a field
     * nobody reads any more.
     *
     * Deliberately not reapplied once a schedule already carries the marker —
     * [now] would otherwise creep forward on every push, and the timestamp is
     * meant to answer "how long has this actually been gone", for a pruning
     * horizon this milestone leaves undecided (see #40).
     */
    private fun flattenSchedules(
        schedules: List<StoredSchedule>,
        childId: DeviceId,
        now: Instant,
    ): Map<String, String?> = buildMap {
        val root = "${ReplicationRules.POLICY}/${childId.value}/$PATH_SCHEDULES"
        with(records) {
            schedules.forEach { schedule ->
                val scheduleRoot = "$root/${schedule.id}"
                put("$scheduleRoot/$FIELD_SCHEDULE_NAME", schedule.name)
                put("$scheduleRoot/$FIELD_SCHEDULE_START", schedule.startMinuteOfDay.toString())
                put("$scheduleRoot/$FIELD_SCHEDULE_END", schedule.endMinuteOfDay.toString())
                put("$scheduleRoot/$FIELD_SCHEDULE_MODE", schedule.mode.name)
                put("$scheduleRoot/$FIELD_SCHEDULE_ENABLED", schedule.enabled.toString())
                put("$scheduleRoot/$FIELD_SCHEDULE_KIND", schedule.kind.name)
                put("$scheduleRoot/$FIELD_SCHEDULE_PARITY_ANCHOR", schedule.parityAnchorEpochDay?.toString())
                putMembers("$scheduleRoot/$FIELD_SCHEDULE_DAYS", schedule.days.map(Int::toString).toSet())
                putMembers("$scheduleRoot/$FIELD_SCHEDULE_PACKAGES", schedule.packages)
            }

            tombstoneGone(root, schedules.map { it.id }.toSet(), FIELD_SCHEDULE_DELETED, now)
        }
    }

    companion object {
        const val PATH_SCHEDULES = "schedules"
        const val FIELD_SCHEDULE_NAME = "name"
        const val FIELD_SCHEDULE_START = "start"
        const val FIELD_SCHEDULE_END = "end"
        const val FIELD_SCHEDULE_MODE = "mode"
        const val FIELD_SCHEDULE_ENABLED = "enabled"
        const val FIELD_SCHEDULE_DAYS = "days"
        const val FIELD_SCHEDULE_PACKAGES = "packages"
        const val FIELD_SCHEDULE_KIND = "kind"

        /** See [ScheduleBlock.parityAnchor]. Absent means every week, same as there. */
        const val FIELD_SCHEDULE_PARITY_ANCHOR = "parityAnchorEpochDay"

        /**
         * A schedule id under this marker is gone, whatever its other fields say.
         * The value is the second it was marked, not a bare flag — see
         * [flattenSchedules] for why, and #40 for the pruning horizon this leaves
         * unresolved.
         */
        const val FIELD_SCHEDULE_DELETED = "deleted"
    }
}
