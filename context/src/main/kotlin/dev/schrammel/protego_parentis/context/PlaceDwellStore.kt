package dev.schrammel.protego_parentis.context

import android.content.Context
import androidx.core.content.edit
import dev.schrammel.protego_parentis.model.PlaceDwell
import dev.schrammel.protego_parentis.model.PlaceId
import dev.schrammel.protego_parentis.model.PlaceReading

/**
 * Where [PlaceDwell] is kept between checks, so a pending arrival survives the
 * process being killed between its two fixes.
 *
 * One state for the phone. The place rules and the arrival alerts both read
 * through [advance], so they can't disagree about whether the phone is at a
 * place. Reading the same fix twice changes nothing, so two callers in one
 * check don't count as two readings.
 */
internal class PlaceDwellStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun current(): PlaceDwell = synchronized(LOCK) { read() }

    fun advance(reading: PlaceReading, known: Set<PlaceId>): PlaceDwell = synchronized(LOCK) {
        val next = read().advance(reading, known)
        write(next)
        next
    }

    private fun read(): PlaceDwell = PlaceDwell(
        confirmed = prefs.getStringSet(KEY_CONFIRMED, emptySet()).orEmpty().mapTo(mutableSetOf(), ::PlaceId),
        pendingSince = prefs.getStringSet(KEY_PENDING, emptySet()).orEmpty()
            .mapNotNull { entry ->
                val id = entry.substringBeforeLast(SEPARATOR)
                val since = entry.substringAfterLast(SEPARATOR).toLongOrNull() ?: return@mapNotNull null
                PlaceId(id) to since
            }
            .toMap(),
        seeded = prefs.getBoolean(KEY_SEEDED, false),
    )

    private fun write(state: PlaceDwell) = prefs.edit {
        putStringSet(KEY_CONFIRMED, state.confirmed.mapTo(mutableSetOf()) { it.value })
        putStringSet(KEY_PENDING, state.pendingSince.mapTo(mutableSetOf()) { (id, since) -> "${id.value}$SEPARATOR$since" })
        putBoolean(KEY_SEEDED, state.seeded)
    }

    private companion object {
        const val FILE_NAME = "place_dwell"
        const val KEY_CONFIRMED = "confirmed"
        const val KEY_PENDING = "pending"
        const val KEY_SEEDED = "seeded"

        /** After the id, so an id containing it still splits correctly. */
        const val SEPARATOR = '|'

        val LOCK = Any()
    }
}
