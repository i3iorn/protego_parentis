package dev.schrammel.protego_parentis.ui.rules

import android.content.Context
import dev.schrammel.protego_parentis.enforcement.EnforcementService
import dev.schrammel.protego_parentis.model.AutoApproval
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.Place
import dev.schrammel.protego_parentis.model.PlaceId
import dev.schrammel.protego_parentis.model.PlaceRef
import dev.schrammel.protego_parentis.rules.AutoApprovalStore
import dev.schrammel.protego_parentis.rules.ContextRuleStore
import dev.schrammel.protego_parentis.rules.LocalPolicyStore
import dev.schrammel.protego_parentis.rules.PolicySnapshot
import dev.schrammel.protego_parentis.rules.ScheduleStore
import dev.schrammel.protego_parentis.rules.StoredCategory
import dev.schrammel.protego_parentis.rules.StoredSchedule
import dev.schrammel.protego_parentis.rules.applySnapshot
import dev.schrammel.protego_parentis.rules.snapshot
import dev.schrammel.protego_parentis.sync.AppInventoryStore
import dev.schrammel.protego_parentis.sync.DeviceId
import dev.schrammel.protego_parentis.sync.PolicyReplication
import dev.schrammel.protego_parentis.sync.SyncCoordinator
import dev.schrammel.protego_parentis.ui.RuleTarget
import dev.schrammel.protego_parentis.usage.AppInfo
import dev.schrammel.protego_parentis.usage.InstalledApps

/**
 * One interface per rule kind, two implementations: this device's own local
 * store, or another device's ruleset reached through [PolicyReplication].
 *
 * Deliberately not collapsed into a single code path through
 * [PolicyReplication] for both kinds of phone: [PolicyReplication.write] and
 * its siblings return no ops — silently — when [dev.schrammel.protego_parentis.sync.LocalDeviceId]
 * cannot produce an identity, a real and documented case (a keystore key
 * invalidated by a biometric change, the moments before first unlock). The
 * managed phone's own edits go straight to the local store precisely so that
 * case never costs it its own rules; only a *remote* edit has anywhere else
 * to go. Local edits still reach the family — the existing push loop in
 * `SyncReceiver.keepReplicaCurrent` already carries them out.
 */
interface PolicyRuleStore {
    fun read(): PolicySnapshot?
    fun write(snapshot: PolicySnapshot)
}

class LocalPolicyRuleStore(private val context: Context) : PolicyRuleStore {
    private val store = LocalPolicyStore(context)
    override fun read(): PolicySnapshot = store.snapshot()
    override fun write(snapshot: PolicySnapshot) {
        store.applySnapshot(snapshot)
        EnforcementService.start(context)
    }
}

class RemotePolicyRuleStore(private val context: Context, private val childId: DeviceId) : PolicyRuleStore {
    override fun read(): PolicySnapshot? = PolicyReplication(context).read(childId)
    override fun write(snapshot: PolicySnapshot) = SyncCoordinator(context).applyPolicyEdit(snapshot, childId)
}

fun policyRuleStore(context: Context, target: RuleTarget): PolicyRuleStore =
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

interface ScheduleRuleStore {
    fun read(): List<StoredSchedule>
    fun write(schedules: List<StoredSchedule>)
}

class LocalScheduleRuleStore(private val context: Context) : ScheduleRuleStore {
    private val store = ScheduleStore(context)
    override fun read(): List<StoredSchedule> = store.schedules
    override fun write(schedules: List<StoredSchedule>) {
        store.schedules = schedules
        EnforcementService.start(context)
    }
}

class RemoteScheduleRuleStore(private val context: Context, private val childId: DeviceId) : ScheduleRuleStore {
    override fun read(): List<StoredSchedule> = PolicyReplication(context).readSchedules(childId)
    override fun write(schedules: List<StoredSchedule>) =
        SyncCoordinator(context).applyScheduleEdit(schedules, childId)
}

fun scheduleRuleStore(context: Context, target: RuleTarget): ScheduleRuleStore =
    if (target.isOwnDevice) LocalScheduleRuleStore(context) else RemoteScheduleRuleStore(context, target.childId)

/** [ScheduleStore]'s find/upsert/delete convenience shape, generalised to either device. */
fun ScheduleRuleStore.find(id: String): StoredSchedule? = read().firstOrNull { it.id == id }

fun ScheduleRuleStore.upsert(schedule: StoredSchedule) {
    write(read().filterNot { it.id == schedule.id } + schedule)
}

fun ScheduleRuleStore.delete(id: String) {
    write(read().filterNot { it.id == id })
}

interface ContextRuleRuleStore {
    fun read(): List<ContextRule>
    fun write(rules: List<ContextRule>)
}

class LocalContextRuleRuleStore(private val context: Context) : ContextRuleRuleStore {
    private val store = ContextRuleStore(context)
    override fun read(): List<ContextRule> = store.rules
    override fun write(rules: List<ContextRule>) {
        store.rules = rules
        EnforcementService.start(context)
    }
}

class RemoteContextRuleRuleStore(private val context: Context, private val childId: DeviceId) : ContextRuleRuleStore {
    override fun read(): List<ContextRule> = PolicyReplication(context).readContextRules(childId)
    override fun write(rules: List<ContextRule>) =
        SyncCoordinator(context).applyContextRuleEdit(rules, childId)
}

fun contextRuleRuleStore(context: Context, target: RuleTarget): ContextRuleRuleStore =
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
interface AutoApprovalRuleStore {
    fun read(): List<AutoApproval>
    fun write(rules: List<AutoApproval>)
}

class LocalAutoApprovalRuleStore(private val context: Context) : AutoApprovalRuleStore {
    private val store = AutoApprovalStore(context)
    override fun read(): List<AutoApproval> = store.rules
    override fun write(rules: List<AutoApproval>) {
        store.rules = rules
    }
}

class RemoteAutoApprovalRuleStore(private val context: Context, private val childId: DeviceId) : AutoApprovalRuleStore {
    override fun read(): List<AutoApproval> = PolicyReplication(context).readAutoApprovals(childId)
    override fun write(rules: List<AutoApproval>) =
        SyncCoordinator(context).applyAutoApprovalEdit(rules, childId)
}

fun autoApprovalRuleStore(context: Context, target: RuleTarget): AutoApprovalRuleStore =
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
 * deliberately outside this interface: that still only ever happens on
 * whichever device holds the coordinate (see [PlaceRef]'s own doc comment),
 * so both implementations here only rename, resize or delete a place that
 * already exists.
 */
interface PlaceRuleStore {
    fun read(): List<PlaceRef>
    fun rename(id: PlaceId, name: String)
    fun resize(id: PlaceId, radiusMetres: Int)
    fun delete(id: PlaceId)
}

class LocalPlaceRuleStore(private val context: Context) : PlaceRuleStore {
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

class RemotePlaceRuleStore(private val context: Context, private val childId: DeviceId) : PlaceRuleStore {
    override fun read(): List<PlaceRef> = PolicyReplication(context).readPlaceRefs(childId)

    override fun rename(id: PlaceId, name: String) = edit(id) { it.copy(name = name) }
    override fun resize(id: PlaceId, radiusMetres: Int) = edit(id) { it.copy(radiusMetres = radiusMetres) }
    override fun delete(id: PlaceId) {
        SyncCoordinator(context).applyPlaceRefEdit(read().filterNot { it.id == id }, childId)
    }

    private fun edit(id: PlaceId, change: (PlaceRef) -> PlaceRef) {
        val refs = read()
        val updated = refs.map { if (it.id == id) change(it) else it }
        SyncCoordinator(context).applyPlaceRefEdit(updated, childId)
    }
}

fun placeRuleStore(context: Context, target: RuleTarget): PlaceRuleStore =
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
}

class LocalAppCatalog(private val context: Context) : AppCatalog {
    override fun launchable(): List<AppInfo> = InstalledApps(context).launchable()
}

class RemoteAppCatalog(private val context: Context, private val childId: DeviceId) : AppCatalog {
    override fun launchable(): List<AppInfo> =
        AppInventoryStore(context).apps(childId).map { AppInfo(PackageName(it.packageName), it.label, icon = null) }
}

fun appCatalog(context: Context, target: RuleTarget): AppCatalog =
    if (target.isOwnDevice) LocalAppCatalog(context) else RemoteAppCatalog(context, target.childId)

/** A package's label, or the package name itself if this catalog has never heard of it. */
fun AppCatalog.labelFor(pkg: PackageName): String =
    launchable().firstOrNull { it.pkg == pkg }?.label ?: pkg.value
