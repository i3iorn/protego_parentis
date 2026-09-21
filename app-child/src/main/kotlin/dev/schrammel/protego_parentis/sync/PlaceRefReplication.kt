package dev.schrammel.protego_parentis.sync

import android.content.Context
import dev.schrammel.protego_parentis.model.PlaceId
import dev.schrammel.protego_parentis.model.PlaceRef
import dev.schrammel.protego_parentis.rules.ContextRuleStore
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.sync.core.ReplicationRules
import dev.schrammel.protego_parentis.sync.core.StateOp
import java.time.Instant

/**
 * Places, stripped to [PlaceRef], as replicated state —
 * [PolicyReplication]'s delegate for [ContextRuleStore.places].
 */
internal class PlaceRefReplication(context: Context) {

    private val appContext = context.applicationContext
    private val store = ContextRuleStore(appContext)
    private val records = PolicyRecordReplication(appContext)
    private val state get() = records.state

    /**
     * Records [refs] as [childId]'s replicated place list — id/name/radius
     * only, never a coordinate. See [PlaceRef] for why: only
     * [dev.schrammel.protego_parentis.model.Place] itself, kept local to
     * whichever device holds it, has one.
     */
    fun writePlaceRefs(
        refs: List<PlaceRef>,
        childId: DeviceId?,
        now: Instant = Instant.now(),
    ): List<StateOp> = records.write(childId) { id -> flattenPlaceRefs(refs, id, now) }

    /**
     * [childId]'s replicated places, excluding any this replica has been
     * told is gone — see [FIELD_PLACE_DELETED] on [flattenPlaceRefs], the
     * same deleted-marker approach [ScheduleReplication.readSchedules] uses.
     */
    fun readPlaceRefs(childId: DeviceId?): List<PlaceRef> {
        val id = childId ?: return emptyList()
        val root = "${ReplicationRules.POLICY}/${id.value}/$PATH_PLACES"
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
     * writes, stripped to what [PlaceRef] carries —
     * [ContextRuleReplication.pushContextRulesFromLocal]'s equivalent, for
     * places.
     */
    fun pushPlaceRefsFromLocal(childId: DeviceId?): List<StateOp> =
        writePlaceRefs(
            store.places.map { PlaceRef(it.id, it.name, it.radiusMetres) },
            childId,
        )

    /**
     * Folds this replica's places back onto [ContextRuleStore], the places
     * equivalent of [ContextRuleReplication.pullContextRulesIntoLocal] —
     * with one structural difference: a place this device holds no local
     * coordinate for is left alone rather than materialised, because there
     * is nothing to materialise it *with*. Only a rename, a resize, or a
     * delete of a place this device already knows ever applies here;
     * creating one, or moving it, still has to happen on whichever device
     * holds its coordinate.
     */
    fun pullPlaceRefsIntoLocal(childId: DeviceId?) {
        val id = childId ?: return
        val root = "${ReplicationRules.POLICY}/${id.value}/$PATH_PLACES"
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
     * [FIELD_PLACE_DELETED] marker, the same shape
     * [ScheduleReplication.flattenSchedules] uses and for the same reason.
     */
    private fun flattenPlaceRefs(
        refs: List<PlaceRef>,
        childId: DeviceId,
        now: Instant,
    ): Map<String, String?> = buildMap {
        val root = "${ReplicationRules.POLICY}/${childId.value}/$PATH_PLACES"
        refs.forEach { ref ->
            val placeRoot = "$root/${ref.id.value}"
            put("$placeRoot/$FIELD_PLACE_NAME", ref.name)
            put("$placeRoot/$FIELD_PLACE_RADIUS", ref.radiusMetres.toString())
        }

        with(records) { tombstoneGone(root, refs.map { it.id.value }.toSet(), FIELD_PLACE_DELETED, now) }
    }

    companion object {
        const val PATH_PLACES = "places"
        const val FIELD_PLACE_NAME = "name"
        const val FIELD_PLACE_RADIUS = "radius"

        /** As [ScheduleReplication.FIELD_SCHEDULE_DELETED], for a place. */
        const val FIELD_PLACE_DELETED = "deleted"
    }
}
