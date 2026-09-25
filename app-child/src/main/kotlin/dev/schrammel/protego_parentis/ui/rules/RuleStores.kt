package dev.schrammel.protego_parentis.ui.rules

import android.content.Context
import dev.schrammel.protego_parentis.AppRegistry
import dev.schrammel.protego_parentis.model.AutoApproval
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.Place
import dev.schrammel.protego_parentis.model.PlaceId
import dev.schrammel.protego_parentis.model.PlaceRef
import dev.schrammel.protego_parentis.model.RuleEffect
import dev.schrammel.protego_parentis.model.RuleTarget
import dev.schrammel.protego_parentis.rules.AutoApprovalStore
import dev.schrammel.protego_parentis.rules.ContextRuleStore
import dev.schrammel.protego_parentis.rules.LocalPolicyStore
import dev.schrammel.protego_parentis.rules.PolicySnapshot
import dev.schrammel.protego_parentis.rules.ScheduleStore
import dev.schrammel.protego_parentis.rules.StoredCategory
import dev.schrammel.protego_parentis.rules.applySnapshot
import dev.schrammel.protego_parentis.rules.snapshot
import dev.schrammel.protego_parentis.sync.AppInventoryStore
import dev.schrammel.protego_parentis.sync.PolicyReplication
import dev.schrammel.protego_parentis.sync.SyncGraph
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.usage.AppInfo
import dev.schrammel.protego_parentis.usage.InstalledApps

/**
 * One interface per rule kind, two implementations: this device's own local
 * store, or another device's ruleset reached through [PolicyReplication].
 *
 * Deliberately not collapsed into a single code path through
 * [PolicyReplication] for both kinds of phone: [PolicyReplication.write] and
 * its siblings return no ops — silently — when [dev.schrammel.protego_parentis.security.LocalDeviceId]
 * cannot produce an identity, a real and documented case (a keystore key
 * invalidated by a biometric change, the moments before first unlock). The
 * managed phone's own edits go straight to the local store precisely so that
 * case never costs it its own rules; only a *remote* edit has anywhere else
 * to go. Local edits still reach the family — the existing push loop in
 * `SyncReceiver.keepReplicaCurrent` already carries them out.
 */
interface PolicyRuleStore : RuleWriteKind {
    fun read(): PolicySnapshot?
    fun write(snapshot: PolicySnapshot)
}

/**
 * Whether writes to a rule store travel over sync instead of landing on this
 * phone. The five editor screens used to re-branch on `target.isOwnDevice`
 * just to decide whether to say "save sent remotely" — the store already knows
 * which kind it is, so it says so here and the screens ask it.
 */
interface RuleWriteKind {
    val writesRemotely: Boolean
}


class LocalPolicyRuleStore(private val context: Context) : PolicyRuleStore, RuleWriteKind {
    override val writesRemotely: Boolean = false
    private val store = LocalPolicyStore(context)
    override fun read(): PolicySnapshot = store.snapshot()
    override fun write(snapshot: PolicySnapshot) {
        store.applySnapshot(snapshot)
        AppRegistry.ruleMutationSink.rulesChanged()
    }
}

class RemotePolicyRuleStore(private val context: Context, private val childId: DeviceId) :
    PolicyRuleStore, RuleWriteKind {
    override val writesRemotely: Boolean = true
    override fun read(): PolicySnapshot? = PolicyReplication(context).read(childId)
    override fun write(snapshot: PolicySnapshot) =
        SyncGraph.get(context).applyPolicyEdit(snapshot, childId)
}

fun policyRuleStore(context: Context, target: RuleScope): PolicyRuleStore =
    if (target.isOwnDevice) LocalPolicyRuleStore(context) else RemotePolicyRuleStore(context, target.childId)

/** [LocalPolicyStore.categories]'s three helpers, generalised to either device. */
fun PolicyRuleStore.categories(): List<StoredCategory> = read()?.categories.orEmpty()

fun PolicyRuleStore.upsertCategory(category: StoredCategory) {
    val snapshot = read() ?: return
    write(snapshot.copy(categories = snapshot.categories.filterNot { it.id == category.id } + category))
}

fun PolicyRuleStore.deleteCategory(id: String) {
    val snapshot = read() ?: return
    write(snapshot.copy(categories = snapshot.categories.filterNot { it.id == id }))
}

interface ContextRuleRuleStore : RuleWriteKind {
    fun read(): List<ContextRule>
    fun write(rules: List<ContextRule>)
}

class LocalContextRuleRuleStore(private val context: Context) : ContextRuleRuleStore,
    RuleWriteKind {
    override val writesRemotely: Boolean = false
    private val store = ContextRuleStore(context)
    override fun read(): List<ContextRule> = store.rules
    override fun write(rules: List<ContextRule>) {
        store.rules = rules
        AppRegistry.ruleMutationSink.rulesChanged()
    }
}

class RemoteContextRuleRuleStore(private val context: Context, private val childId: DeviceId) :
    ContextRuleRuleStore, RuleWriteKind {
    override val writesRemotely: Boolean = true
    override fun read(): List<ContextRule> = PolicyReplication(context).readContextRules(childId)
    override fun write(rules: List<ContextRule>) =
        SyncGraph.get(context).applyContextRuleEdit(rules, childId)
}

fun contextRuleRuleStore(context: Context, target: RuleScope): ContextRuleRuleStore =
    if (target.isOwnDevice) LocalContextRuleRuleStore(context) else RemoteContextRuleRuleStore(context, target.childId)

/** [ContextRuleStore]'s upsert/delete convenience shape, generalised to either device. */
fun ContextRuleRuleStore.find(id: String): ContextRule? = read().firstOrNull { it.id == id }

fun ContextRuleRuleStore.upsert(rule: ContextRule) {
    write(read().filterNot { it.id == rule.id } + rule)
}

fun ContextRuleRuleStore.delete(id: String) {
    write(read().filterNot { it.id == id })
}

/**
 * The family's standing answers to requests (RQ-1), either device's (#89).
 *
 * Answering a request in the moment still only ever happens standing at the
 * phone the request came from — that stays own-device-only. Deciding *when*
 * a standing yes should apply is a rule like any other, though, and #89 was
 * exactly the gap where every other rule kind had already grown this
 * abstraction and auto-approval had not.
 */
interface AutoApprovalRuleStore : RuleWriteKind {
    fun read(): List<AutoApproval>
    fun write(rules: List<AutoApproval>)
}

class LocalAutoApprovalRuleStore(private val context: Context) : AutoApprovalRuleStore,
    RuleWriteKind {
    override val writesRemotely: Boolean = false
    private val store = AutoApprovalStore(context)
    override fun read(): List<AutoApproval> = store.rules
    override fun write(rules: List<AutoApproval>) {
        store.rules = rules
        // The mutation is the trigger: every other local store's write path
        // re-kicks enforcement, and this one's silence meant an auto-approval
        // edit re-applied only whenever some other mutation happened to fire.
        AppRegistry.ruleMutationSink.rulesChanged()
    }
}

class RemoteAutoApprovalRuleStore(private val context: Context, private val childId: DeviceId) :
    AutoApprovalRuleStore, RuleWriteKind {
    override val writesRemotely: Boolean = true
    override fun read(): List<AutoApproval> = PolicyReplication(context).readAutoApprovals(childId)
    override fun write(rules: List<AutoApproval>) =
        SyncGraph.get(context).applyAutoApprovalEdit(rules, childId)
}

fun autoApprovalRuleStore(context: Context, target: RuleScope): AutoApprovalRuleStore =
    if (target.isOwnDevice) LocalAutoApprovalRuleStore(context) else RemoteAutoApprovalRuleStore(context, target.childId)

/** [AutoApprovalStore]'s find/upsert/delete convenience shape, generalised to either device. */
fun AutoApprovalRuleStore.find(id: String): AutoApproval? = read().firstOrNull { it.id == id }

fun AutoApprovalRuleStore.upsert(rule: AutoApproval) {
    write(read().filterNot { it.id == rule.id } + rule)
}

fun AutoApprovalRuleStore.delete(id: String) {
    write(read().filterNot { it.id == id })
}

/**
 * Places, edited as [PlaceRef] — id/name/radius, never a coordinate — on
 * whichever kind of phone. Creating a *new* place, or moving one, is
 * deliberately outside this interface, since every method here works on a
 * place that already has a coordinate somewhere and this type never carries
 * one (see [PlaceRef]'s own doc comment). A parent's phone can still bring a
 * new place into existence — see
 * [dev.schrammel.protego_parentis.ui.PlacesActivity.addPositionRemotely] and
 * [dev.schrammel.protego_parentis.ui.PlacesActivity.addPlaceRemotely] — just
 * not through this interface: both paths ask the managed phone to create it,
 * over
 * [dev.schrammel.protego_parentis.sync.core.SyncMessageType.PLACE_CREATE_REQUESTED],
 * rather than writing a coordinate-free ref here that would have nowhere to
 * get a coordinate from.
 */
interface PlaceRuleStore : RuleWriteKind {
    fun read(): List<PlaceRef>
    fun rename(id: PlaceId, name: String)
    fun resize(id: PlaceId, radiusMetres: Int)
    fun delete(id: PlaceId)
}

class LocalPlaceRuleStore(private val context: Context) : PlaceRuleStore, RuleWriteKind {
    override val writesRemotely: Boolean = false
    private val store = ContextRuleStore(context)
    override fun read(): List<PlaceRef> = store.places.map { PlaceRef(it.id, it.name, it.radiusMetres) }

    override fun rename(id: PlaceId, name: String) = edit(id) { it.copy(name = name) }
    override fun resize(id: PlaceId, radiusMetres: Int) = edit(id) { it.copy(radiusMetres = radiusMetres) }
    override fun delete(id: PlaceId) = store.deletePlace(id)

    private fun edit(id: PlaceId, change: (Place) -> Place) {
        val place = store.places.firstOrNull { it.id == id } ?: return
        store.upsertPlace(change(place))
    }
}

class RemotePlaceRuleStore(private val context: Context, private val childId: DeviceId) :
    PlaceRuleStore, RuleWriteKind {
    override val writesRemotely: Boolean = true
    override fun read(): List<PlaceRef> = PolicyReplication(context).readPlaceRefs(childId)

    override fun rename(id: PlaceId, name: String) = edit(id) { it.copy(name = name) }
    override fun resize(id: PlaceId, radiusMetres: Int) = edit(id) { it.copy(radiusMetres = radiusMetres) }
    override fun delete(id: PlaceId) {
        SyncGraph.get(context).applyPlaceRefEdit(read().filterNot { it.id == id }, childId)
    }

    private fun edit(id: PlaceId, change: (PlaceRef) -> PlaceRef) {
        val refs = read()
        val updated = refs.map { if (it.id == id) change(it) else it }
        SyncGraph.get(context).applyPlaceRefEdit(updated, childId)
    }
}

fun placeRuleStore(context: Context, target: RuleScope): PlaceRuleStore =
    if (target.isOwnDevice) LocalPlaceRuleStore(context) else RemotePlaceRuleStore(context, target.childId)

/**
 * Which apps a screen has to offer for choosing, either device's.
 *
 * The one place these two kinds of phone are structurally, permanently
 * unequal rather than just differently sourced: [InstalledApps] can load an
 * icon because it is asking this device's own package manager, and
 * [AppInventoryStore] never can, because a label and a package name are all
 * [dev.schrammel.protego_parentis.sync.AppInventoryEntry] was ever built to
 * carry over the wire. A remote picker draws every row without one rather
 * than pretending otherwise.
 */
interface AppCatalog {
    fun launchable(): List<AppInfo>

    /**
     * A package's label, or the package name itself if this catalog has
     * never heard of it. Default implementation only — see [LocalAppCatalog]
     * for why the real device's own catalog does not use it.
     */
    fun labelFor(pkg: PackageName): String = launchable().firstOrNull { it.pkg == pkg }?.label ?: pkg.value
}

class LocalAppCatalog(private val context: Context) : AppCatalog {
    private val installed = InstalledApps(context)

    override fun launchable(): List<AppInfo> = installed.launchable()

    // The default implementation's launchable().firstOrNull { ... } means a
    // full PackageManager scan of every installed app just to find the label
    // of one — cheap once launchable() is already cached for this list, but
    // this is called once per app in a category (or a rule's target), each
    // one paying for the whole scan again the first time it runs cold.
    // InstalledApps.labelFor is the same lookup this app already keeps
    // cached per package for exactly this reason.
    override fun labelFor(pkg: PackageName): String = installed.labelFor(pkg)
}

class RemoteAppCatalog(private val context: Context, private val childId: DeviceId) : AppCatalog {

    /**
     * Read once per catalog instance: [labelFor] used to fall back to the
     * interface default's `launchable().firstOrNull { ... }`, which is a full
     * inventory read per package asked about — N labels in a category summary
     * cost N reads. One read, memoised here, answers all of them.
     */
    private val apps: List<AppInfo> by lazy {
        AppInventoryStore(context).apps(childId).map { AppInfo(PackageName(it.packageName), it.label, icon = null) }
    }

    override fun launchable(): List<AppInfo> = apps

    override fun labelFor(pkg: PackageName): String =
        apps.firstOrNull { it.pkg == pkg }?.label ?: pkg.value
}

fun appCatalog(context: Context, target: RuleScope): AppCatalog =
    if (target.isOwnDevice) LocalAppCatalog(context) else RemoteAppCatalog(context, target.childId)

// ---------- legacy-field-to-rule-id translation ----------
//
// AppControlActivity, CategoryEditActivity and AppLimitsActivity each edit
// one specific migrated ContextRule — the well-known id
// LocalPolicyStore.migrateAppRulesToUnifiedRules/migrateToUnifiedRules gives
// a legacy per-app block, per-category budget, or per-app budget. The id
// scheme and the "find it in this rule list" lookup used to be hand-rolled
// once per screen; centralised here so a change to either only has one place
// to happen, not three that can quietly drift apart.

/** The well-known [ContextRule] id [pkg]'s migrated block rule uses. */
fun blockRuleIdFor(pkg: PackageName): String = LocalPolicyStore.MIGRATED_APP_BLOCK_ID_PREFIX + pkg.value

/** Whether [pkg] has a block rule at [blockRuleIdFor], among [rules]. */
fun isAppBlocked(pkg: PackageName, rules: List<ContextRule>): Boolean =
    rules.firstOrNull { it.id == blockRuleIdFor(pkg) }?.effect is RuleEffect.Block

/** The well-known [ContextRule] id [categoryId]'s migrated budget rule uses. */
fun categoryBudgetRuleIdFor(categoryId: String): String =
    LocalPolicyStore.MIGRATED_CATEGORY_BUDGET_ID_PREFIX + categoryId

/** [categoryId]'s budget as its migrated [ContextRule] says it, among [rules], or null if there is none. */
fun categoryBudgetMinutes(rules: List<ContextRule>, categoryId: String): Int? {
    val rule = rules.firstOrNull { it.id == categoryBudgetRuleIdFor(categoryId) } ?: return null
    val effect = rule.effect as? RuleEffect.SetBudget ?: return null
    return effect.budget.default.inWholeMinutes.toInt()
}

/** The well-known [ContextRule] id [pkg]'s migrated per-app budget rule uses. */
fun appBudgetRuleIdFor(pkg: PackageName): String = LocalPolicyStore.MIGRATED_APP_BUDGET_ID_PREFIX + pkg.value

/** The well-known [ContextRule] id the migrated bedtime schedule uses. */
fun bedtimeRuleId(): String = LocalPolicyStore.MIGRATED_SCHEDULE_ID_PREFIX + ScheduleStore.BEDTIME_ID

/** Every migrated per-app budget among [rules], as the plain package-to-minutes map the UI wants. */
fun appBudgetLimits(rules: List<ContextRule>): Map<String, Int> = rules.mapNotNull { rule ->
    val effect = rule.effect as? RuleEffect.SetBudget ?: return@mapNotNull null
    val onlyTarget = (rule.target as? RuleTarget.Apps)?.packages?.singleOrNull() ?: return@mapNotNull null
    if (rule.id != appBudgetRuleIdFor(onlyTarget)) return@mapNotNull null
    onlyTarget.value to effect.budget.default.inWholeMinutes.toInt()
}.toMap()
