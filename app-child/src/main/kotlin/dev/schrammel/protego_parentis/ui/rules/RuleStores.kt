package dev.schrammel.protego_parentis.ui.rules

import android.content.Context
import dev.schrammel.protego_parentis.enforcement.EnforcementService
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.model.Place
import dev.schrammel.protego_parentis.model.PlaceId
import dev.schrammel.protego_parentis.model.PlaceRef
import dev.schrammel.protego_parentis.rules.ContextRuleStore
import dev.schrammel.protego_parentis.rules.LocalPolicyStore
import dev.schrammel.protego_parentis.rules.PolicySnapshot
import dev.schrammel.protego_parentis.rules.ScheduleStore
import dev.schrammel.protego_parentis.rules.StoredSchedule
import dev.schrammel.protego_parentis.rules.applySnapshot
import dev.schrammel.protego_parentis.rules.snapshot
import dev.schrammel.protego_parentis.sync.DeviceId
import dev.schrammel.protego_parentis.sync.PolicyReplication
import dev.schrammel.protego_parentis.sync.SyncCoordinator
import dev.schrammel.protego_parentis.ui.RuleTarget

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
