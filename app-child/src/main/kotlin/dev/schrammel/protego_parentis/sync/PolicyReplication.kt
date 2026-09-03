package dev.schrammel.protego_parentis.sync

import android.content.Context
import androidx.core.content.edit
import dev.schrammel.protego_parentis.model.InstallPolicy
import dev.schrammel.protego_parentis.model.ScheduleBlock
import dev.schrammel.protego_parentis.rules.LocalPolicyStore
import dev.schrammel.protego_parentis.rules.PolicySnapshot
import dev.schrammel.protego_parentis.rules.StoredCategory
import dev.schrammel.protego_parentis.rules.StoredSchedule
import dev.schrammel.protego_parentis.rules.applySnapshot
import dev.schrammel.protego_parentis.rules.snapshot
import dev.schrammel.protego_parentis.security.RoleStore
import java.time.Instant

/**
 * The rules, as paths.
 *
 * The bridge between the two representations the app needs: the domain one that
 * [LocalPolicyStore] and every rules screen already work in, and the flat
 * register space [StateMerge] converges on. [PolicySnapshot] sits in the middle
 * of both directions, so there is one definition of "the editable rules" rather
 * than a second one hidden in here.
 *
 * ## Why collections become paths
 *
 * The tempting shortcut is to store a category's app list as one encoded value.
 * That would make every membership change a write to a single register, which
 * means two parents adding two different apps to the same category are a
 * conflict, and one of them loses — silently, and for no reason a person could
 * work out.
 *
 * So membership is one register per member:
 *
 * ```
 * policy/categories/games/packages/com.example.a = "1"
 * policy/categories/games/packages/com.example.b = "1"
 * ```
 *
 * Now the two parents write different paths, both survive, and removing an app
 * is a tombstone on its own path rather than a rewrite of the whole list. The
 * same reasoning applies to app limits, exemptions, blocks and every other set
 * the policy holds.
 *
 * ## On [PolicySnapshot.version]
 *
 * Carried but no longer load-bearing. It existed so a whole-policy push could be
 * refused when it was built from a stale copy; under replication there is no
 * such thing as a stale edit — a change to one field merges with a concurrent
 * change to another, and two changes to the *same* field are ordered by their
 * Lamport stamps. Nothing reads it any more, and it is kept only so the type
 * still round-trips through its existing tested codec.
 */
class PolicyReplication(context: Context) {

    private val appContext = context.applicationContext
    private val store = LocalPolicyStore(appContext)
    private val state = ReplicatedStateStore(appContext)
    private val roster = RosterStore(appContext)

    /** Which rules version was last flattened into the replica, on this phone. */
    private val prefs = appContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /**
     * The one managed phone in the family, when this phone knows of exactly one.
     *
     * A family can now have more than one, and a caller that means a particular
     * child must say so explicitly by passing [DeviceId]. This exists only so a
     * single-child family — still by far the common case — keeps working exactly
     * as it did before this class knew about scoping at all. Choosing *which*
     * child on a multi-child family's screens is a UI question for later work,
     * not something this class can answer on its own.
     */
    private fun defaultChildId(): DeviceId? = roster.child()?.id

    /**
     * Records this phone's own rules as replicated writes.
     *
     * Called on the managed phone after any local rule edit, and again on every
     * poll. Unchanged fields produce no ops, so the common case — a screen
     * re-rendering, or a save that moved one slider — publishes one line rather
     * than the whole policy, and a poll that changed nothing publishes nothing.
     *
     * Skipped outright when the rules have not moved since the last push. The
     * comparison below is not expensive in absolute terms, but the managed
     * phone's enforcement tick runs every few seconds, and reading every rule
     * off disk and flattening it into two hundred paths to conclude "no change"
     * is a poor thing to do all day on a battery. [Policy.version] increments on
     * every local write, so it is a sufficient answer to "is this worth doing".
     */
    fun pushFromLocalPolicy(childId: DeviceId? = defaultChildId()): List<StateOp> {
        val version = store.load().version
        if (version == prefs.getLong(KEY_PUSHED_VERSION, NEVER)) return emptyList()

        // A phone that cannot say who it is has not pushed anything, and must not
        // record that it has. The keystore is genuinely allowed to be
        // unavailable — a key invalidated by a biometric change, a restored
        // backup, the moments before first unlock — and [write] correctly
        // produces nothing then. Marking the version as pushed anyway would
        // leave the rules unpublished and the gate shut behind them, until
        // somebody happened to edit a rule. That is the same failure the gate
        // itself introduced, one step further along.
        if (LocalDeviceId.current() == null) return emptyList()

        val ops = write(store.snapshot(), childId)
        prefs.edit { putLong(KEY_PUSHED_VERSION, version) }
        return ops
    }

    /**
     * Forgets that these rules were ever pushed, so the next push sends them all.
     *
     * Called when the replica is wiped, which is not something the version gate
     * above can see: the rules themselves have not changed, so their version has
     * not moved, so without this the managed phone would join its next family
     * and never publish a single rule to it. The parents would sit on an empty
     * Rules screen until somebody happened to edit something — the exact silent
     * failure the gate was added alongside fixing.
     */
    fun forgetPushed() = prefs.edit { remove(KEY_PUSHED_VERSION) }

    /**
     * Records an edit to [childId]'s rules.
     *
     * Called both from the managed phone, editing its own rules under its own
     * id, and from a parent's phone, editing the rules of whichever child the
     * screen is showing. [ReplicatedStateStore.writeAll] refuses anything this
     * device's role and identity do not permit — a child device passing another
     * child's id here simply produces no ops.
     */
    fun write(snapshot: PolicySnapshot, childId: DeviceId? = defaultChildId()): List<StateOp> {
        val id = childId ?: return emptyList()
        val origin = LocalDeviceId.current() ?: return emptyList()
        val role = RoleStore(appContext).roleOrChild.toSyncRole()
        return state.writeAll(flatten(snapshot, id), origin, role)
    }

    /**
     * Writes the replicated rules back onto this phone.
     *
     * Only ever called on the managed phone, for its own id, and only for
     * state that arrived from a device permitted to write it —
     * [ReplicationRules] has already refused anything else by the time this
     * runs.
     */
    fun pullIntoLocalPolicy(childId: DeviceId? = defaultChildId()) {
        store.applySnapshot(read(childId) ?: return)
    }

    /**
     * [childId]'s replicated rules, or null if none have arrived, or if this
     * phone does not yet know which child [childId] resolves to.
     *
     * Null rather than an empty policy on purpose: a family whose other phones
     * are still on an older build, or a replica before its first exchange, holds
     * no policy paths at all — and applying an empty one would silently clear
     * every rule the family had.
     */
    fun read(childId: DeviceId? = defaultChildId()): PolicySnapshot? {
        val id = childId ?: return null
        val root = "$POLICY/${id.value}"
        val held = state.entries
        if (held.keys.none { it.startsWith("$root/") }) return null

        fun value(path: String): String? = held["$root/$path"]?.value
        fun number(path: String, fallback: Int) = value(path)?.toIntOrNull() ?: fallback

        return PolicySnapshot(
            version = 0L,
            dailyLimitMinutes = number(PATH_DAILY_LIMIT, 0),
            bedtimeEnabled = value(PATH_BEDTIME_ENABLED)?.toBooleanStrictOrNull() ?: false,
            bedtimeStartMinute = number(PATH_BEDTIME_START, 0),
            bedtimeEndMinute = number(PATH_BEDTIME_END, 0),
            bedtimeDays = members("$root/$PATH_BEDTIME_DAYS").mapNotNull(String::toIntOrNull).toSet(),
            categories = state.idsUnder("$root/$PATH_CATEGORIES").mapNotNull { catId ->
                StoredCategory(
                    id = catId,
                    // A category whose name is gone is a category that was
                    // deleted; its other registers may still be catching up.
                    name = value("$PATH_CATEGORIES/$catId/name") ?: return@mapNotNull null,
                    limitMinutes = number("$PATH_CATEGORIES/$catId/limitMinutes", 0),
                    packages = members("$root/$PATH_CATEGORIES/$catId/packages"),
                    subcategories = members("$root/$PATH_CATEGORIES/$catId/subcategories"),
                )
            },
            appLimits = state.children("$root/$PATH_APP_LIMITS")
                .mapNotNull { (pkg, minutes) -> minutes.toIntOrNull()?.let { pkg to it } }
                .toMap(),
            exemptPackages = members("$root/$PATH_EXEMPT"),
            blockedPackages = members("$root/$PATH_BLOCKED"),
            hiddenPackages = members("$root/$PATH_HIDDEN"),
            networkBlockedPackages = members("$root/$PATH_NETWORK_BLOCKED"),
            installPolicy = value(PATH_INSTALL_POLICY) ?: InstallPolicy.NOTIFY.name,
            installWindowUntil = value(PATH_INSTALL_WINDOW)?.toLongOrNull(),
        )
    }

    /**
     * Records [schedules] as [childId]'s replicated named windows.
     *
     * Not yet reachable from [write] or [read]: [PolicySnapshot] does not carry
     * schedules, because building one still means reading two separate local
     * stores ([LocalPolicyStore] and [dev.schrammel.protego_parentis.rules.ScheduleStore])
     * as one, which is its own piece of work. This is the wire format and the
     * merge behaviour, ready for that to call into.
     */
    fun writeSchedules(
        schedules: List<StoredSchedule>,
        childId: DeviceId? = defaultChildId(),
        now: Instant = Instant.now(),
    ): List<StateOp> {
        val id = childId ?: return emptyList()
        val origin = LocalDeviceId.current() ?: return emptyList()
        val role = RoleStore(appContext).roleOrChild.toSyncRole()
        return state.writeAll(flattenSchedules(schedules, id, now), origin, role)
    }

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
    fun readSchedules(childId: DeviceId? = defaultChildId()): List<StoredSchedule> {
        val id = childId ?: return emptyList()
        val root = "$POLICY/${id.value}/$PATH_SCHEDULES"
        return state.idsUnder(root).mapNotNull { scheduleId ->
            val scheduleRoot = "$root/$scheduleId"
            val fields = state.children(scheduleRoot)
            if (FIELD_SCHEDULE_DELETED in fields) return@mapNotNull null

            StoredSchedule(
                id = scheduleId,
                name = fields[FIELD_SCHEDULE_NAME] ?: return@mapNotNull null,
                days = members("$scheduleRoot/$FIELD_SCHEDULE_DAYS").mapNotNull(String::toIntOrNull).toSet(),
                startMinuteOfDay = fields[FIELD_SCHEDULE_START]?.toIntOrNull() ?: return@mapNotNull null,
                endMinuteOfDay = fields[FIELD_SCHEDULE_END]?.toIntOrNull() ?: return@mapNotNull null,
                packages = members("$scheduleRoot/$FIELD_SCHEDULE_PACKAGES"),
                mode = ScheduleBlock.Mode.entries.firstOrNull { it.name == fields[FIELD_SCHEDULE_MODE] }
                    ?: ScheduleBlock.Mode.EXCLUSIVE,
                enabled = fields[FIELD_SCHEDULE_ENABLED]?.toBooleanStrictOrNull() ?: true,
            ).takeIf { it.isComplete }
        }
    }

    /**
     * Every schedule as a path, plus a [FIELD_SCHEDULE_DELETED] marker for one
     * that used to be live and no longer is.
     *
     * This is the one place policy replication departs from how [flatten]
     * deletes a category: a gone category has every one of its paths written to
     * a tombstone, and that is exactly the shape a genuine race can break. A
     * delete and a concurrent rename land on the *same* schedule from two
     * different devices; each writes a different path (`deleted` versus `name`),
     * so [StateMerge] never compares them against each other at all — there is
     * no shared Lamport stamp for "delete" to win or lose against "rename", and
     * the two fields simply resolve independently. If the delete instead nulled
     * `name` itself, whichever write happened to carry the higher stamp would
     * decide the outcome, and a replica that saw the rename's stamp win would go
     * on showing a schedule that every other replica had already dropped.
     *
     * Writing a single `deleted` marker sidesteps that: [readSchedules] excludes
     * a schedule the instant that marker is present, with nothing left to race.
     * A rename arriving after the marker changes a field nobody reads any more.
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
        val root = "$POLICY/${childId.value}/$PATH_SCHEDULES"
        schedules.forEach { schedule ->
            val scheduleRoot = "$root/${schedule.id}"
            put("$scheduleRoot/$FIELD_SCHEDULE_NAME", schedule.name)
            put("$scheduleRoot/$FIELD_SCHEDULE_START", schedule.startMinuteOfDay.toString())
            put("$scheduleRoot/$FIELD_SCHEDULE_END", schedule.endMinuteOfDay.toString())
            put("$scheduleRoot/$FIELD_SCHEDULE_MODE", schedule.mode.name)
            put("$scheduleRoot/$FIELD_SCHEDULE_ENABLED", schedule.enabled.toString())
            putMembers("$scheduleRoot/$FIELD_SCHEDULE_DAYS", schedule.days.map(Int::toString).toSet())
            putMembers("$scheduleRoot/$FIELD_SCHEDULE_PACKAGES", schedule.packages)
        }

        val live = schedules.map { it.id }.toSet()
        state.idsUnder(root)
            .filterNot { it in live }
            .filterNot { FIELD_SCHEDULE_DELETED in state.children("$root/$it") }
            .forEach { gone -> put("$root/$gone/$FIELD_SCHEDULE_DELETED", now.epochSecond.toString()) }
    }

    private fun members(root: String): Set<String> = state.children(root).keys

    /**
     * Every rule as a path, including the ones that should now be absent.
     *
     * Membership that has gone is written as a tombstone rather than omitted: an
     * op saying "this app is no longer in this category" is the only thing that
     * removes it from a replica that still has it. Leaving it out would let
     * every other device keep the app forever, and the next digest repair would
     * hand it back.
     */
    private fun flatten(snapshot: PolicySnapshot, childId: DeviceId): Map<String, String?> = buildMap {
        val root = "$POLICY/${childId.value}"
        put("$root/$PATH_DAILY_LIMIT", snapshot.dailyLimitMinutes.toString())
        put("$root/$PATH_BEDTIME_ENABLED", snapshot.bedtimeEnabled.toString())
        put("$root/$PATH_BEDTIME_START", snapshot.bedtimeStartMinute.toString())
        put("$root/$PATH_BEDTIME_END", snapshot.bedtimeEndMinute.toString())
        putMembers("$root/$PATH_BEDTIME_DAYS", snapshot.bedtimeDays.map(Int::toString).toSet())

        snapshot.categories.forEach { category ->
            val catRoot = "$root/$PATH_CATEGORIES/${category.id}"
            put("$catRoot/name", category.name)
            put("$catRoot/limitMinutes", category.limitMinutes.toString())
            putMembers("$catRoot/packages", category.packages)
            putMembers("$catRoot/subcategories", category.subcategories)
        }
        // A category deleted here has to be deleted everywhere, and its whole
        // subtree with it — a stale name left behind would be a category that
        // reappeared at the next repair.
        val live = snapshot.categories.map { it.id }.toSet()
        state.idsUnder("$root/$PATH_CATEGORIES")
            .filterNot { it in live }
            .forEach { gone ->
                state.entries.keys
                    .filter { it.startsWith("$root/$PATH_CATEGORIES/$gone/") }
                    .forEach { put(it, null) }
            }

        putMembers("$root/$PATH_EXEMPT", snapshot.exemptPackages)
        putMembers("$root/$PATH_BLOCKED", snapshot.blockedPackages)
        putMembers("$root/$PATH_HIDDEN", snapshot.hiddenPackages)
        putMembers("$root/$PATH_NETWORK_BLOCKED", snapshot.networkBlockedPackages)
        putValues("$root/$PATH_APP_LIMITS", snapshot.appLimits.mapValues { it.value.toString() })

        put("$root/$PATH_INSTALL_POLICY", snapshot.installPolicy)
        put("$root/$PATH_INSTALL_WINDOW", snapshot.installWindowUntil?.toString())
    }

    private fun MutableMap<String, String?>.putMembers(root: String, members: Set<String>) =
        putValues(root, members.associateWith { PRESENT })

    private fun MutableMap<String, String?>.putValues(root: String, values: Map<String, String>) {
        values.forEach { (key, value) -> put("$root/$key", value) }
        state.children(root).keys
            .filterNot { it in values }
            .forEach { gone -> put("$root/$gone", null) }
    }

    private companion object {
        const val POLICY = ReplicationRules.POLICY
        const val PRESENT = "1"

        const val FILE_NAME = "policy_replication"
        const val KEY_PUSHED_VERSION = "pushedVersion"

        /** No version has been pushed; [Policy.version] starts above this. */
        const val NEVER = -1L

        const val PATH_DAILY_LIMIT = "dailyLimitMinutes"
        const val PATH_BEDTIME_ENABLED = "bedtime/enabled"
        const val PATH_BEDTIME_START = "bedtime/start"
        const val PATH_BEDTIME_END = "bedtime/end"
        const val PATH_BEDTIME_DAYS = "bedtime/days"
        const val PATH_CATEGORIES = "categories"
        const val PATH_APP_LIMITS = "appLimits"
        const val PATH_EXEMPT = "exempt"
        const val PATH_BLOCKED = "blocked"
        const val PATH_HIDDEN = "hidden"
        const val PATH_NETWORK_BLOCKED = "networkBlocked"
        const val PATH_INSTALL_POLICY = "installPolicy"
        const val PATH_INSTALL_WINDOW = "installWindowUntil"

        const val PATH_SCHEDULES = "schedules"
        const val FIELD_SCHEDULE_NAME = "name"
        const val FIELD_SCHEDULE_START = "start"
        const val FIELD_SCHEDULE_END = "end"
        const val FIELD_SCHEDULE_MODE = "mode"
        const val FIELD_SCHEDULE_ENABLED = "enabled"
        const val FIELD_SCHEDULE_DAYS = "days"
        const val FIELD_SCHEDULE_PACKAGES = "packages"

        /**
         * A schedule id under this marker is gone, whatever its other fields say.
         * The value is the second it was marked, not a bare flag — see
         * [flattenSchedules] for why, and #40 for the pruning horizon this leaves
         * unresolved.
         */
        const val FIELD_SCHEDULE_DELETED = "deleted"
    }
}
