package dev.schrammel.dementor_watch.rules

import android.content.Context
import androidx.core.content.edit
import dev.schrammel.dementor_watch.model.PackageName
import dev.schrammel.dementor_watch.model.ScheduleBlock
import dev.schrammel.dementor_watch.model.ScheduleId
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * A named schedule as it is stored and edited (SC-2).
 *
 * Separate from the domain [ScheduleBlock] because the two have different jobs.
 * The domain type refuses to exist in an invalid state — no days, no name, a
 * window covering the whole day — which is exactly right for the engine and
 * exactly wrong for an editor, where half-finished is a normal thing to be
 * holding while someone decides what to type next.
 */
data class StoredSchedule(
    val id: String,
    val name: String,
    /** ISO day numbers, 1 = Monday. */
    val days: Set<Int>,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    /** Apps that stay usable inside the window. */
    val allowedPackages: Set<String> = emptySet(),
    val enabled: Boolean = true,
) {
    val start: LocalTime get() = minuteOfDay(startMinuteOfDay)
    val end: LocalTime get() = minuteOfDay(endMinuteOfDay)

    /** Whether the engine would accept this. The editor refuses to save otherwise. */
    val isComplete: Boolean
        get() = name.isNotBlank() && days.isNotEmpty() && startMinuteOfDay != endMinuteOfDay

    /** Null when incomplete, so one unfinished row cannot break the whole policy. */
    fun toDomain(): ScheduleBlock? {
        if (!isComplete) return null
        return ScheduleBlock(
            id = ScheduleId(id),
            name = name.trim(),
            days = days.mapNotNull { day -> DayOfWeek.entries.firstOrNull { it.value == day } }
                .toSet()
                .ifEmpty { return null },
            start = start,
            end = end,
            allowedPackages = allowedPackages.map(::PackageName).toSet(),
            kind = ScheduleBlock.Kind.CUSTOM,
        )
    }

    private fun minuteOfDay(value: Int): LocalTime {
        val clamped = value.coerceIn(0, MINUTES_PER_DAY - 1)
        return LocalTime.of(clamped / MINUTES_PER_HOUR, clamped % MINUTES_PER_HOUR)
    }

    companion object {
        const val MINUTES_PER_HOUR = 60
        const val MINUTES_PER_DAY = 24 * 60

        fun minuteOfDay(time: LocalTime): Int = time.hour * MINUTES_PER_HOUR + time.minute
    }
}

/**
 * The family's named schedule windows.
 *
 * Bedtime is deliberately not one of these. It has its own switch on the limits
 * screen, its own defaults and its own wind-down warning, and folding it in here
 * would mean a family could delete bedtime by accident while tidying up their
 * homework block.
 */
class ScheduleStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var schedules: List<StoredSchedule>
        get() = decode(prefs.getString(KEY_SCHEDULES, null))
        set(value) = prefs.edit { putString(KEY_SCHEDULES, encode(value)) }

    fun find(id: String): StoredSchedule? = schedules.firstOrNull { it.id == id }

    fun upsert(schedule: StoredSchedule) {
        schedules = schedules.filterNot { it.id == schedule.id } + schedule
    }

    fun delete(id: String) {
        schedules = schedules.filterNot { it.id == id }
    }

    /** Only complete, enabled windows reach the engine. */
    fun active(): List<ScheduleBlock> =
        schedules.filter { it.enabled }.mapNotNull { it.toDomain() }

    private fun encode(schedules: List<StoredSchedule>): String =
        JSONArray(
            schedules.map { schedule ->
                JSONObject().apply {
                    put(KEY_ID, schedule.id)
                    put(KEY_NAME, schedule.name)
                    put(KEY_DAYS, JSONArray(schedule.days.sorted()))
                    put(KEY_START, schedule.startMinuteOfDay)
                    put(KEY_END, schedule.endMinuteOfDay)
                    put(KEY_ALLOWED, JSONArray(schedule.allowedPackages.sorted()))
                    put(KEY_ENABLED, schedule.enabled)
                }
            },
        ).toString()

    private fun decode(raw: String?): List<StoredSchedule> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let(::fromJson)
        }
    }

    private fun fromJson(json: JSONObject): StoredSchedule? {
        val id = json.optString(KEY_ID).takeIf(String::isNotBlank) ?: return null
        return StoredSchedule(
            id = id,
            name = json.optString(KEY_NAME),
            days = json.optJSONArray(KEY_DAYS).toIntSet(),
            startMinuteOfDay = json.optInt(KEY_START, 0),
            endMinuteOfDay = json.optInt(KEY_END, 0),
            allowedPackages = json.optJSONArray(KEY_ALLOWED).toStringSet(),
            enabled = json.optBoolean(KEY_ENABLED, true),
        )
    }

    private fun JSONArray?.toIntSet(): Set<Int> =
        this?.let { array -> (0 until array.length()).map(array::optInt).toSet() }.orEmpty()

    private fun JSONArray?.toStringSet(): Set<String> =
        this?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
        }.orEmpty().toSet()

    private companion object {
        const val FILE_NAME = "schedules"
        const val KEY_SCHEDULES = "schedules"

        const val KEY_ID = "id"
        const val KEY_NAME = "name"
        const val KEY_DAYS = "days"
        const val KEY_START = "start"
        const val KEY_END = "end"
        const val KEY_ALLOWED = "allowed"
        const val KEY_ENABLED = "enabled"
    }
}
