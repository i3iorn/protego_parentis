package dev.schrammel.protego_parentis.sync

import android.content.Context
import androidx.core.content.edit
import dev.schrammel.protego_parentis.model.AutoApproval
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.model.InstallPolicy
import dev.schrammel.protego_parentis.model.PlaceRef
import dev.schrammel.protego_parentis.rules.DnsProfile
import dev.schrammel.protego_parentis.rules.LocalPolicyStore
import dev.schrammel.protego_parentis.rules.PolicySnapshot
import dev.schrammel.protego_parentis.rules.StoredCategory
import dev.schrammel.protego_parentis.rules.StoredSchedule
import dev.schrammel.protego_parentis.rules.applySnapshot
import dev.schrammel.protego_parentis.rules.snapshot
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.RoleStore
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.sync.core.ReplicationRules
import dev.schrammel.protego_parentis.sync.core.StateOp
import java.time.Instant
import dev.schrammel.protego_parentis.sync.core.Enrolment
import dev.schrammel.protego_parentis.sync.core.StateMerge
import dev.schrammel.protego_parentis.security.LocalDeviceId

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
 *
 * ## Why this class delegates rather than does everything itself
 *
 * Five nearly-independent record types replicate through here — policy
 * fields (this class's own pipeline), schedules, contextual rules, place
 * refs, and auto-approvals — each with its own `push*FromLocal`/
 * `pull*IntoLocal`/`write*`/`read*`/`flatten*` shape and its own reason to
 * change. [ScheduleReplication], [ContextRuleReplication],
 * [PlaceRefReplication] and [AutoApprovalReplication] each own one of those;
 * this class stays the facade every caller already knows, and the one place
 * [defaultChildId]/[backfillIfNeeded] can coordinate across all five without
 * each of those callers gaining four extra classes to construct. The shared
 * wiring beneath all five lives in [PolicyRecordReplication].
 */
class PolicyReplication(context: Context) {

    private val appContext = context.applicationContext
    private val store = LocalPolicyStore(appContext)
    private val roster = RosterStore(appContext)
    private val records = PolicyRecordReplication(appContext)
    private val state get() = records.state

    private val schedules = ScheduleReplication(appContext)
    private val contextRules = ContextRuleReplication(appContext)
    private val placeRefs = PlaceRefReplication(appContext)
    private val autoApprovals = AutoApprovalReplication(appContext)

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
     *
     * That last sentence used to be aspirational rather than true: a device
     * is never actually enrolled into its *own* roster — the same self-check
     * that keeps a roster entry from ever claiming a different child also
     * means the managed phone's own roster holds *zero* children, not one,
     * since [RosterReplication.pullIntoRoster] and [Enrolment.shouldAccept]
     * both refuse a roster entry or vouch naming the receiving device. On the
     * managed phone, [RosterStore.child] was therefore always null, so every
     * caller here silently no-opped on its own rules — the parent's write
     * landed in [ReplicatedStateStore], correctly, but nothing ever pulled it
     * back into [LocalPolicyStore]/[dev.schrammel.protego_parentis.rules.ContextRuleStore] for this phone to read
     * or enforce, and a local edit here never made it out either. The managed
     * phone's own id needs no roster lookup at all — it is simply who this
     * phone already knows itself to be.
     */
    private fun defaultChildId(): DeviceId? =
        if (RoleStore(appContext).roleOrChild == DeviceRole.CHILD) {
            LocalDeviceId.current()?.let(::DeviceId)
        } else {
            roster.child()?.id
        }

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
        val version = store.version
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
     * One-time recovery for a phone whose replica already held everything
     * correctly, but never once materialised it locally.
     *
     * Before this existed, [defaultChildId] resolved via [RosterStore.child],
     * which can never find this phone's own entry in its own roster — a
     * managed phone with a single child (by far the common case) always had
     * [defaultChildId] come back null there. Every `pull*IntoLocal` call
     * this class offers no-ops on a null childId, so a rule set while that
     * bug was live landed correctly in [ReplicatedStateStore] — which is
     * why a parent's own screen, reading that same store, always showed it
     * as expected — but was silently never pulled into this phone's own
     * [LocalPolicyStore]/[dev.schrammel.protego_parentis.rules.ContextRuleStore], which is what actually
     * enforces anything.
     *
     * Fixing [defaultChildId] only fixes what happens *next*: `onStateChanged`
     * in [ReplicaMaintainer] only re-pulls when a *new* op arrives, and a rule
     * that has not changed since the bug was live generates no new op —
     * the replica already agrees with the parent bit for bit, so nothing
     * about it looks different to reconcile. Without a forced backfill, a
     * family's existing rules would stay stuck exactly as they were even
     * after every phone updated to the fix.
     *
     * Gated on a real identity being available, the same reason every
     * `push*` above already is: a null [defaultChildId] here would mean
     * every `pull*IntoLocal` below reads an empty subtree and overwrites
     * this phone's own local stores with nothing, on a phone that may
     * simply have a momentarily unavailable keystore rather than nothing
     * left to pull. The flag stays unset in that case, so the next
     * construction of [dev.schrammel.protego_parentis.enforcement.EnforcementCoordinator]
     * tries again rather than this being it forever.
     *
     * Also gated on the replica actually holding *something* for this id.
     * A phone that has never once synced — freshly paired, or set up with
     * local-only rules before its first successful poll — has an empty
     * replica for a legitimate reason, not because a pull-worthy rule is
     * stuck there; pulling anyway would silently replace those local rules
     * with nothing, which is a worse bug than the one this recovers from.
     * This check does *not* set the flag on its own: once the phone does
     * sync for the first time, the very next construction sees something
     * to pull and runs for real, which is the correct point for this to
     * happen anyway.
     */
    fun backfillIfNeeded() {
        if (RoleStore(appContext).roleOrChild != DeviceRole.CHILD) return
        if (prefs.getBoolean(KEY_BACKFILLED_V1, false)) return
        val id = LocalDeviceId.current() ?: return
        if (state.idsUnder("$POLICY${ReplicationRules.SEPARATOR}$id").isEmpty()) return
        pullIntoLocalPolicy()
        pullSchedulesIntoLocal()
        pullContextRulesIntoLocal()
        pullPlaceRefsIntoLocal()
        pullAutoApprovalsIntoLocal()
        prefs.edit { putBoolean(KEY_BACKFILLED_V1, true) }
    }

    /**
     * Records an edit to [childId]'s rules.
     *
     * Called both from the managed phone, editing its own rules under its own
     * id, and from a parent's phone, editing the rules of whichever child the
     * screen is showing. [ReplicatedStateStore.writeAll] refuses anything this
     * device's role and identity do not permit — a child device passing another
     * child's id here simply produces no ops.
     */
    fun write(snapshot: PolicySnapshot, childId: DeviceId? = defaultChildId()): List<StateOp> =
        records.write(childId) { id -> flatten(snapshot, id) }

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

    /** As [ScheduleReplication.writeSchedules], for [childId]'s named windows. */
    fun writeSchedules(
        schedules: List<StoredSchedule>,
        childId: DeviceId? = defaultChildId(),
        now: Instant = Instant.now(),
    ): List<StateOp> = this.schedules.writeSchedules(schedules, childId, now)

    /** As [ScheduleReplication.readSchedules]. */
    fun readSchedules(childId: DeviceId? = defaultChildId()): List<StoredSchedule> =
        schedules.readSchedules(childId)

    /** As [ScheduleReplication.pushSchedulesFromLocal]. */
    fun pushSchedulesFromLocal(childId: DeviceId? = defaultChildId()): List<StateOp> =
        schedules.pushSchedulesFromLocal(childId)

    /** As [ScheduleReplication.pullSchedulesIntoLocal]. */
    fun pullSchedulesIntoLocal(childId: DeviceId? = defaultChildId()) =
        schedules.pullSchedulesIntoLocal(childId)

    /** As [ContextRuleReplication.writeContextRules], for [childId]'s contextual rules. */
    fun writeContextRules(
        rules: List<ContextRule>,
        childId: DeviceId? = defaultChildId(),
        now: Instant = Instant.now(),
    ): List<StateOp> = contextRules.writeContextRules(rules, childId, now)

    /** As [ContextRuleReplication.readContextRules]. */
    fun readContextRules(childId: DeviceId? = defaultChildId()): List<ContextRule> =
        contextRules.readContextRules(childId)

    /** As [ContextRuleReplication.pushContextRulesFromLocal]. */
    fun pushContextRulesFromLocal(childId: DeviceId? = defaultChildId()): List<StateOp> =
        contextRules.pushContextRulesFromLocal(childId)

    /** As [ContextRuleReplication.pullContextRulesIntoLocal]. */
    fun pullContextRulesIntoLocal(childId: DeviceId? = defaultChildId()) =
        contextRules.pullContextRulesIntoLocal(childId)

    /** As [PlaceRefReplication.writePlaceRefs], for [childId]'s place list. */
    fun writePlaceRefs(
        refs: List<PlaceRef>,
        childId: DeviceId? = defaultChildId(),
        now: Instant = Instant.now(),
    ): List<StateOp> = placeRefs.writePlaceRefs(refs, childId, now)

    /** As [PlaceRefReplication.readPlaceRefs]. */
    fun readPlaceRefs(childId: DeviceId? = defaultChildId()): List<PlaceRef> =
        placeRefs.readPlaceRefs(childId)

    /** As [PlaceRefReplication.pushPlaceRefsFromLocal]. */
    fun pushPlaceRefsFromLocal(childId: DeviceId? = defaultChildId()): List<StateOp> =
        placeRefs.pushPlaceRefsFromLocal(childId)

    /** As [PlaceRefReplication.pullPlaceRefsIntoLocal]. */
    fun pullPlaceRefsIntoLocal(childId: DeviceId? = defaultChildId()) =
        placeRefs.pullPlaceRefsIntoLocal(childId)

    /** As [AutoApprovalReplication.writeAutoApprovals], for [childId]'s auto-approval rules. */
    fun writeAutoApprovals(
        rules: List<AutoApproval>,
        childId: DeviceId? = defaultChildId(),
        now: Instant = Instant.now(),
    ): List<StateOp> = autoApprovals.writeAutoApprovals(rules, childId, now)

    /** As [AutoApprovalReplication.readAutoApprovals]. */
    fun readAutoApprovals(childId: DeviceId? = defaultChildId()): List<AutoApproval> =
        autoApprovals.readAutoApprovals(childId)

    /** As [AutoApprovalReplication.pushAutoApprovalsFromLocal]. */
    fun pushAutoApprovalsFromLocal(childId: DeviceId? = defaultChildId()): List<StateOp> =
        autoApprovals.pushAutoApprovalsFromLocal(childId)

    /** As [AutoApprovalReplication.pullAutoApprovalsIntoLocal]. */
    fun pullAutoApprovalsIntoLocal(childId: DeviceId? = defaultChildId()) =
        autoApprovals.pullAutoApprovalsIntoLocal(childId)

    private fun members(root: String): Set<String> = records.members(root)

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

        with(records) {
            snapshot.categories.forEach { category ->
                val catRoot = "$root/$PATH_CATEGORIES/${category.id}"
                put("$catRoot/name", category.name)
                put("$catRoot/limitMinutes", category.limitMinutes.toString())
                putMembers("$catRoot/packages", category.packages)
                putMembers("$catRoot/subcategories", category.subcategories)
            }
            // A category deleted here has to be deleted everywhere, and its
            // whole subtree with it — a stale name left behind would be a
            // category that reappeared at the next repair.
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
    }

    private companion object {
        const val POLICY = ReplicationRules.POLICY

        const val FILE_NAME = "policy_replication"
        const val KEY_PUSHED_VERSION = "pushedVersion"

        /** See [backfillIfNeeded]'s own doc comment for what this one-time flag recovers from. */
        const val KEY_BACKFILLED_V1 = "remote_rules_backfilled_v1"

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
    }
}
