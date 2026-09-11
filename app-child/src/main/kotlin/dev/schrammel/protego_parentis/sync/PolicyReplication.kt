package dev.schrammel.protego_parentis.sync

import android.content.Context
import androidx.core.content.edit
import dev.schrammel.protego_parentis.model.AutoApproval
import dev.schrammel.protego_parentis.model.AutoApprovalWindow
import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.model.InstallPolicy
import dev.schrammel.protego_parentis.model.PlaceId
import dev.schrammel.protego_parentis.model.PlaceRef
import dev.schrammel.protego_parentis.model.ScheduleBlock
import dev.schrammel.protego_parentis.rules.AutoApprovalStore
import dev.schrammel.protego_parentis.rules.ContextRuleCodec
import dev.schrammel.protego_parentis.rules.ContextRuleStore
import dev.schrammel.protego_parentis.rules.DnsProfile
import dev.schrammel.protego_parentis.rules.LocalPolicyStore
import dev.schrammel.protego_parentis.rules.PolicySnapshot
import dev.schrammel.protego_parentis.rules.ScheduleStore
import dev.schrammel.protego_parentis.rules.StoredCategory
import dev.schrammel.protego_parentis.rules.StoredSchedule
import dev.schrammel.protego_parentis.rules.applySnapshot
import dev.schrammel.protego_parentis.rules.snapshot
import dev.schrammel.protego_parentis.security.RoleStore
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.sync.core.Enrolment
import dev.schrammel.protego_parentis.sync.core.ReplicationRules
import dev.schrammel.protego_parentis.sync.core.StateMerge
import dev.schrammel.protego_parentis.sync.core.StateOp

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
     *
     * Re-checked for #45, now that a second managed phone can actually be
     * enrolled rather than only represented: still safe. Every real caller of
     * this class already runs on the managed phone about its own rules, and
     * [RosterStore.child] on that phone can never resolve to a *different*
     * child — [RosterReplication.pullIntoRoster] refuses any roster entry
     * claiming a CHILD role regardless of which role the receiving device
     * itself holds, the same as [Enrolment.shouldAccept] refuses a vouch for
     * one. A managed phone's own roster holds at most itself among children.
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
            webFilterProfile = value(PATH_WEB_PROFILE) ?: DnsProfile.OFF.name,
            webFilterCustomHost = value(PATH_WEB_CUSTOM_HOST).orEmpty(),
            webFilterSafeSearch = value(PATH_WEB_SAFE_SEARCH)?.toBooleanStrictOrNull() ?: false,
            webFilterYoutube = value(PATH_WEB_YOUTUBE)?.toBooleanStrictOrNull() ?: false,
            webFilterIncognito = value(PATH_WEB_INCOGNITO)?.toBooleanStrictOrNull() ?: false,
            webFilterBlockedUrls = members("$root/$PATH_WEB_BLOCKED_URLS"),
            webFilterAllowedUrls = members("$root/$PATH_WEB_ALLOWED_URLS"),
            webFilterSuspendBrowsers = value(PATH_WEB_SUSPEND_BROWSERS)?.toBooleanStrictOrNull() ?: false,
            webFilterCountVisits = value(PATH_WEB_COUNT_VISITS)?.toBooleanStrictOrNull() ?: false,
            goalEnabled = value(PATH_GOAL_ENABLED)?.toBooleanStrictOrNull() ?: false,
            goalMaxMinutes = number(PATH_GOAL_MAX_MINUTES, 0),
            goalPrize = value(PATH_GOAL_PRIZE).orEmpty(),
            disabledFeatures = members("$root/$PATH_DISABLED_FEATURES"),
        )
    }

    /**
     * Records [schedules] as [childId]'s replicated named windows.
     *
     * Deliberately not folded into [write]/[read]: [PolicySnapshot] does not
     * carry schedules, and giving it a `schedules` field would mean every
     * caller that builds or applies one — including the whole-policy
     * `POLICY_SNAPSHOT`/`POLICY_UPDATED` backward-compatibility path — takes on
     * a field it may not need. [pushSchedulesFromLocal]/[pullSchedulesIntoLocal]
     * are the actual bridge to [dev.schrammel.protego_parentis.rules.ScheduleStore].
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
                kind = ScheduleBlock.Kind.entries.firstOrNull { it.name == fields[FIELD_SCHEDULE_KIND] }
                    ?: ScheduleBlock.Kind.CUSTOM,
                parityAnchorEpochDay = fields[FIELD_SCHEDULE_PARITY_ANCHOR]?.toLongOrNull(),
            ).takeIf { it.isComplete }
        }
    }

    /**
     * Records this phone's own [dev.schrammel.protego_parentis.rules.ScheduleStore]
     * as replicated writes, the schedule equivalent of [pushFromLocalPolicy].
     *
     * Called on the managed phone on every poll, same as the rest of policy.
     * No version gate: unlike [pushFromLocalPolicy], [ScheduleStore] carries no
     * counter of its own to check first, and [writeSchedules] already costs
     * nothing when nothing changed — [ReplicatedStateStore.writeAll] diffs
     * against what is already held before queuing anything.
     */
    fun pushSchedulesFromLocal(childId: DeviceId? = defaultChildId()): List<StateOp> =
        writeSchedules(ScheduleStore(appContext).schedules, childId)

    /**
     * Writes this replica's schedules back onto [dev.schrammel.protego_parentis.rules.ScheduleStore],
     * the schedule equivalent of [pullIntoLocalPolicy].
     *
     * Only ever meaningful on the managed phone — a parent's phone has no
     * local [ScheduleStore] anything enforces. Called only alongside a
     * POLICY change this replica just received, the same trigger
     * [pullIntoLocalPolicy] uses, and with the same characteristic: a schedule
     * whose fields are still arriving is read as gone or unfinished until the
     * rest catches up, exactly as a policy field briefly reads as its default
     * during the same window.
     */
    fun pullSchedulesIntoLocal(childId: DeviceId? = defaultChildId()) {
        ScheduleStore(appContext).schedules = readSchedules(childId)
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
            put("$scheduleRoot/$FIELD_SCHEDULE_KIND", schedule.kind.name)
            put("$scheduleRoot/$FIELD_SCHEDULE_PARITY_ANCHOR", schedule.parityAnchorEpochDay?.toString())
            putMembers("$scheduleRoot/$FIELD_SCHEDULE_DAYS", schedule.days.map(Int::toString).toSet())
            putMembers("$scheduleRoot/$FIELD_SCHEDULE_PACKAGES", schedule.packages)
        }

        val live = schedules.map { it.id }.toSet()
        state.idsUnder(root)
            .filterNot { it in live }
            .filterNot { FIELD_SCHEDULE_DELETED in state.children("$root/$it") }
            .forEach { gone -> put("$root/$gone/$FIELD_SCHEDULE_DELETED", now.epochSecond.toString()) }
    }

    /**
     * Records [rules] as [childId]'s replicated contextual rules — the
     * condition/effect/target shape's equivalent of [writeSchedules].
     *
     * Not yet reachable from a screen: this milestone is wiring only, per the
     * same "one PR per record type" split #40/#41 used for schedules — a
     * condition can nest `AllOf`/`AnyOf`/`Not` arbitrarily, and a parent-phone
     * builder for that is its own, more careful piece of work. This is the
     * wire format and the merge behaviour, ready for that to call into.
     */
    fun writeContextRules(
        rules: List<ContextRule>,
        childId: DeviceId? = defaultChildId(),
        now: Instant = Instant.now(),
    ): List<StateOp> {
        val id = childId ?: return emptyList()
        val origin = LocalDeviceId.current() ?: return emptyList()
        val role = RoleStore(appContext).roleOrChild.toSyncRole()
        return state.writeAll(flattenContextRules(rules, id, now), origin, role)
    }

    /**
     * [childId]'s replicated contextual rules, excluding any this replica has
     * been told is gone — see [FIELD_RULE_DELETED] on [flattenContextRules],
     * the same deleted-marker approach [readSchedules] uses and for the same
     * reason: a delete and a concurrent edit to the same rule write different
     * paths, so which one a replica saw last never decides whether the rule
     * is still there.
     *
     * A rule whose `condition`, `effect` or `target` has not replicated yet,
     * or does not parse — an unrecognised kind from a newer build, say — is
     * excluded the same way [ContextRuleStore.decodeRules] already drops one
     * it cannot make sense of; nothing here is new risk beyond what local
     * storage already tolerates.
     */
    fun readContextRules(childId: DeviceId? = defaultChildId()): List<ContextRule> {
        val id = childId ?: return emptyList()
        val root = "$POLICY/${id.value}/$PATH_CONTEXT_RULES"
        return state.idsUnder(root).mapNotNull { ruleId ->
            val ruleRoot = "$root/$ruleId"
            val fields = state.children(ruleRoot)
            if (FIELD_RULE_DELETED in fields) return@mapNotNull null

            ContextRule(
                id = ruleId,
                name = fields[FIELD_RULE_NAME] ?: return@mapNotNull null,
                enabled = fields[FIELD_RULE_ENABLED]?.toBooleanStrictOrNull() ?: true,
                condition = ContextRuleCodec.decodeConditionFromString(fields[FIELD_RULE_CONDITION])
                    ?: Condition.Always,
                effect = ContextRuleCodec.decodeEffectFromString(fields[FIELD_RULE_EFFECT])
                    ?: return@mapNotNull null,
                target = ContextRuleCodec.decodeTargetFromString(fields[FIELD_RULE_TARGET])
                    ?: return@mapNotNull null,
            )
        }
    }

    /**
     * Records this phone's own [ContextRuleStore] rules as replicated writes
     * — [pushSchedulesFromLocal]'s equivalent. Places are pushed separately,
     * by [pushPlaceRefsFromLocal] — not folded in here because the store
     * happens to hold both, and because what a place replicates as
     * ([PlaceRef]) is deliberately not the same shape as what it is
     * ([dev.schrammel.protego_parentis.model.Place]).
     */
    fun pushContextRulesFromLocal(childId: DeviceId? = defaultChildId()): List<StateOp> =
        writeContextRules(ContextRuleStore(appContext).rules, childId)

    /** [pullSchedulesIntoLocal]'s equivalent, for contextual rules. */
    fun pullContextRulesIntoLocal(childId: DeviceId? = defaultChildId()) {
        ContextRuleStore(appContext).rules = readContextRules(childId)
    }

    /**
     * Every contextual rule as a path, plus a [FIELD_RULE_DELETED] marker —
     * see [flattenSchedules] for why a marker rather than nulling every field.
     */
    private fun flattenContextRules(
        rules: List<ContextRule>,
        childId: DeviceId,
        now: Instant,
    ): Map<String, String?> = buildMap {
        val root = "$POLICY/${childId.value}/$PATH_CONTEXT_RULES"
        rules.forEach { rule ->
            val ruleRoot = "$root/${rule.id}"
            put("$ruleRoot/$FIELD_RULE_NAME", rule.name)
            put("$ruleRoot/$FIELD_RULE_ENABLED", rule.enabled.toString())
            put("$ruleRoot/$FIELD_RULE_CONDITION", ContextRuleCodec.encodeConditionToString(rule.condition))
            put("$ruleRoot/$FIELD_RULE_EFFECT", ContextRuleCodec.encodeEffectToString(rule.effect))
            put("$ruleRoot/$FIELD_RULE_TARGET", ContextRuleCodec.encodeTargetToString(rule.target))
        }

        val live = rules.map { it.id }.toSet()
        state.idsUnder(root)
            .filterNot { it in live }
            .filterNot { FIELD_RULE_DELETED in state.children("$root/$it") }
            .forEach { gone -> put("$root/$gone/$FIELD_RULE_DELETED", now.epochSecond.toString()) }
    }

    /**
     * Records [refs] as [childId]'s replicated place list — id/name/radius
     * only, never a coordinate. See [PlaceRef] for why: only
     * [dev.schrammel.protego_parentis.model.Place] itself, kept local to
     * whichever device holds it, has one.
     */
    fun writePlaceRefs(
        refs: List<PlaceRef>,
        childId: DeviceId? = defaultChildId(),
        now: Instant = Instant.now(),
    ): List<StateOp> {
        val id = childId ?: return emptyList()
        val origin = LocalDeviceId.current() ?: return emptyList()
        val role = RoleStore(appContext).roleOrChild.toSyncRole()
        return state.writeAll(flattenPlaceRefs(refs, id, now), origin, role)
    }

    /**
     * [childId]'s replicated places, excluding any this replica has been
     * told is gone — see [FIELD_PLACE_DELETED] on [flattenPlaceRefs], the
     * same deleted-marker approach [readSchedules] uses.
     */
    fun readPlaceRefs(childId: DeviceId? = defaultChildId()): List<PlaceRef> {
        val id = childId ?: return emptyList()
        val root = "$POLICY/${id.value}/$PATH_PLACES"
        return state.idsUnder(root).mapNotNull { placeId ->
            val fields = state.children("$root/$placeId")
            if (FIELD_PLACE_DELETED in fields) return@mapNotNull null

            PlaceRef(
                id = PlaceId(placeId),
                name = fields[FIELD_PLACE_NAME] ?: return@mapNotNull null,
                radiusMetres = fields[FIELD_PLACE_RADIUS]?.toIntOrNull() ?: return@mapNotNull null,
            )
        }
    }

    /**
     * Records this phone's own [ContextRuleStore] places as replicated
     * writes, stripped to what [PlaceRef] carries — [pushContextRulesFromLocal]'s
     * equivalent, for places.
     */
    fun pushPlaceRefsFromLocal(childId: DeviceId? = defaultChildId()): List<StateOp> =
        writePlaceRefs(
            ContextRuleStore(appContext).places.map { PlaceRef(it.id, it.name, it.radiusMetres) },
            childId,
        )

    /**
     * Folds this replica's places back onto [ContextRuleStore], the places
     * equivalent of [pullContextRulesIntoLocal] — with one structural
     * difference: a place this device holds no local coordinate for is left
     * alone rather than materialised, because there is nothing to
     * materialise it *with*. Only a rename, a resize, or a delete of a place
     * this device already knows ever applies here; creating one, or moving
     * it, still has to happen on whichever device holds its coordinate.
     */
    fun pullPlaceRefsIntoLocal(childId: DeviceId? = defaultChildId()) {
        val id = childId ?: return
        val root = "$POLICY/${id.value}/$PATH_PLACES"
        val store = ContextRuleStore(appContext)
        val localById = store.places.associateBy { it.id }

        state.idsUnder(root).forEach { placeId ->
            val place = localById[PlaceId(placeId)] ?: return@forEach
            val fields = state.children("$root/$placeId")

            if (FIELD_PLACE_DELETED in fields) {
                store.deletePlace(place.id)
                return@forEach
            }

            val name = fields[FIELD_PLACE_NAME] ?: return@forEach
            val radius = fields[FIELD_PLACE_RADIUS]?.toIntOrNull() ?: return@forEach
            if (name != place.name || radius != place.radiusMetres) {
                store.upsertPlace(place.copy(name = name, radiusMetres = radius))
            }
        }
    }

    /**
     * Every place as a path — id/name/radius only — plus a
     * [FIELD_PLACE_DELETED] marker, the same shape [flattenSchedules] uses
     * and for the same reason.
     */
    private fun flattenPlaceRefs(
        refs: List<PlaceRef>,
        childId: DeviceId,
        now: Instant,
    ): Map<String, String?> = buildMap {
        val root = "$POLICY/${childId.value}/$PATH_PLACES"
        refs.forEach { ref ->
            val placeRoot = "$root/${ref.id.value}"
            put("$placeRoot/$FIELD_PLACE_NAME", ref.name)
            put("$placeRoot/$FIELD_PLACE_RADIUS", ref.radiusMetres.toString())
        }

        val live = refs.map { it.id.value }.toSet()
        state.idsUnder(root)
            .filterNot { it in live }
            .filterNot { FIELD_PLACE_DELETED in state.children("$root/$it") }
            .forEach { gone -> put("$root/$gone/$FIELD_PLACE_DELETED", now.epochSecond.toString()) }
    }

    /**
     * Records [rules] as [childId]'s replicated auto-approval rules — the
     * schedules/contextual-rules shape's equivalent for RQ-1's standing
     * answers.
     *
     * [AutoApproval.target] does not round-trip: [AutoApprovalStore] itself
     * has never persisted it either — no editor screen sets it — so there is
     * nothing here to replicate yet. A rule read back always has `target =
     * null`, exactly as one read from local storage already does.
     */
    fun writeAutoApprovals(
        rules: List<AutoApproval>,
        childId: DeviceId? = defaultChildId(),
        now: Instant = Instant.now(),
    ): List<StateOp> {
        val id = childId ?: return emptyList()
        val origin = LocalDeviceId.current() ?: return emptyList()
        val role = RoleStore(appContext).roleOrChild.toSyncRole()
        return state.writeAll(flattenAutoApprovals(rules, id, now), origin, role)
    }

    /**
     * [childId]'s replicated auto-approval rules, excluding any this replica
     * has been told is gone — see [FIELD_AUTO_DELETED] on
     * [flattenAutoApprovals], the same deleted-marker approach [readSchedules]
     * uses.
     */
    fun readAutoApprovals(childId: DeviceId? = defaultChildId()): List<AutoApproval> {
        val id = childId ?: return emptyList()
        val root = "$POLICY/${id.value}/$PATH_AUTO_APPROVAL"
        return state.idsUnder(root).mapNotNull { ruleId ->
            val fields = state.children("$root/$ruleId")
            if (FIELD_AUTO_DELETED in fields) return@mapNotNull null

            val name = fields[FIELD_AUTO_NAME] ?: return@mapNotNull null
            val minutes = fields[FIELD_AUTO_MAX_MINUTES]?.toIntOrNull() ?: return@mapNotNull null
            val count = fields[FIELD_AUTO_MAX_PER_WINDOW]?.toIntOrNull() ?: return@mapNotNull null
            runCatching {
                AutoApproval(
                    id = ruleId,
                    name = name,
                    enabled = fields[FIELD_AUTO_ENABLED]?.toBooleanStrictOrNull() ?: true,
                    maxAmount = minutes.minutes,
                    maxPerWindow = count,
                    window = AutoApprovalWindow.entries.firstOrNull { it.name == fields[FIELD_AUTO_WINDOW] }
                        ?: AutoApprovalWindow.WEEK,
                )
            }.getOrNull()
        }
    }

    /**
     * Records this phone's own [AutoApprovalStore] rules as replicated
     * writes — [pushContextRulesFromLocal]'s equivalent.
     */
    fun pushAutoApprovalsFromLocal(childId: DeviceId? = defaultChildId()): List<StateOp> =
        writeAutoApprovals(AutoApprovalStore(appContext).rules, childId)

    /** [pullContextRulesIntoLocal]'s equivalent, for auto-approval rules. */
    fun pullAutoApprovalsIntoLocal(childId: DeviceId? = defaultChildId()) {
        AutoApprovalStore(appContext).rules = readAutoApprovals(childId)
    }

    /**
     * Every auto-approval rule as a path, plus a [FIELD_AUTO_DELETED] marker
     * — see [flattenSchedules] for why a marker rather than nulling every
     * field.
     */
    private fun flattenAutoApprovals(
        rules: List<AutoApproval>,
        childId: DeviceId,
        now: Instant,
    ): Map<String, String?> = buildMap {
        val root = "$POLICY/${childId.value}/$PATH_AUTO_APPROVAL"
        rules.forEach { rule ->
            val ruleRoot = "$root/${rule.id}"
            put("$ruleRoot/$FIELD_AUTO_NAME", rule.name)
            put("$ruleRoot/$FIELD_AUTO_ENABLED", rule.enabled.toString())
            put("$ruleRoot/$FIELD_AUTO_MAX_MINUTES", rule.maxAmount.inWholeMinutes.toString())
            put("$ruleRoot/$FIELD_AUTO_MAX_PER_WINDOW", rule.maxPerWindow.toString())
            put("$ruleRoot/$FIELD_AUTO_WINDOW", rule.window.name)
        }

        val live = rules.map { it.id }.toSet()
        state.idsUnder(root)
            .filterNot { it in live }
            .filterNot { FIELD_AUTO_DELETED in state.children("$root/$it") }
            .forEach { gone -> put("$root/$gone/$FIELD_AUTO_DELETED", now.epochSecond.toString()) }
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

        put("$root/$PATH_WEB_PROFILE", snapshot.webFilterProfile)
        put("$root/$PATH_WEB_CUSTOM_HOST", snapshot.webFilterCustomHost)
        put("$root/$PATH_WEB_SAFE_SEARCH", snapshot.webFilterSafeSearch.toString())
        put("$root/$PATH_WEB_YOUTUBE", snapshot.webFilterYoutube.toString())
        put("$root/$PATH_WEB_INCOGNITO", snapshot.webFilterIncognito.toString())
        putMembers("$root/$PATH_WEB_BLOCKED_URLS", snapshot.webFilterBlockedUrls)
        putMembers("$root/$PATH_WEB_ALLOWED_URLS", snapshot.webFilterAllowedUrls)
        put("$root/$PATH_WEB_SUSPEND_BROWSERS", snapshot.webFilterSuspendBrowsers.toString())
        put("$root/$PATH_WEB_COUNT_VISITS", snapshot.webFilterCountVisits.toString())

        put("$root/$PATH_GOAL_ENABLED", snapshot.goalEnabled.toString())
        put("$root/$PATH_GOAL_MAX_MINUTES", snapshot.goalMaxMinutes.toString())
        put("$root/$PATH_GOAL_PRIZE", snapshot.goalPrize)

        putMembers("$root/$PATH_DISABLED_FEATURES", snapshot.disabledFeatures)
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
        const val PATH_CATEGORIES = "categories"
        const val PATH_APP_LIMITS = "appLimits"
        const val PATH_EXEMPT = "exempt"
        const val PATH_BLOCKED = "blocked"
        const val PATH_HIDDEN = "hidden"
        const val PATH_NETWORK_BLOCKED = "networkBlocked"
        const val PATH_INSTALL_POLICY = "installPolicy"
        const val PATH_INSTALL_WINDOW = "installWindowUntil"

        const val PATH_WEB_PROFILE = "webFilter/profile"
        const val PATH_WEB_CUSTOM_HOST = "webFilter/customHost"
        const val PATH_WEB_SAFE_SEARCH = "webFilter/safeSearch"
        const val PATH_WEB_YOUTUBE = "webFilter/youtube"
        const val PATH_WEB_INCOGNITO = "webFilter/incognito"
        const val PATH_WEB_BLOCKED_URLS = "webFilter/blockedUrls"
        const val PATH_WEB_ALLOWED_URLS = "webFilter/allowedUrls"
        const val PATH_WEB_SUSPEND_BROWSERS = "webFilter/suspendBrowsers"
        const val PATH_WEB_COUNT_VISITS = "webFilter/countVisits"

        const val PATH_GOAL_ENABLED = "goal/enabled"
        const val PATH_GOAL_MAX_MINUTES = "goal/maxMinutes"
        const val PATH_GOAL_PRIZE = "goal/prize"

        const val PATH_DISABLED_FEATURES = "disabledFeatures"

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

        const val PATH_CONTEXT_RULES = "contextRules"
        const val FIELD_RULE_NAME = "name"
        const val FIELD_RULE_ENABLED = "enabled"
        const val FIELD_RULE_CONDITION = "condition"
        const val FIELD_RULE_EFFECT = "effect"
        const val FIELD_RULE_TARGET = "target"

        /** As [FIELD_SCHEDULE_DELETED], for a contextual rule. */
        const val FIELD_RULE_DELETED = "deleted"

        const val PATH_PLACES = "places"
        const val FIELD_PLACE_NAME = "name"
        const val FIELD_PLACE_RADIUS = "radius"

        /** As [FIELD_SCHEDULE_DELETED], for a place. */
        const val FIELD_PLACE_DELETED = "deleted"

        const val PATH_AUTO_APPROVAL = "autoApproval"
        const val FIELD_AUTO_NAME = "name"
        const val FIELD_AUTO_ENABLED = "enabled"
        const val FIELD_AUTO_MAX_MINUTES = "maxMinutes"
        const val FIELD_AUTO_MAX_PER_WINDOW = "maxPerWindow"
        const val FIELD_AUTO_WINDOW = "window"

        /** As [FIELD_SCHEDULE_DELETED], for an auto-approval rule. */
        const val FIELD_AUTO_DELETED = "deleted"
    }
}
