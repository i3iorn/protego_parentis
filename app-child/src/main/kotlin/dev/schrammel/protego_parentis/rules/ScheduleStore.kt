package dev.schrammel.protego_parentis.rules

import android.content.Context
import androidx.core.content.edit
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.ScheduleBlock
import dev.schrammel.protego_parentis.model.ScheduleId
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
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
    /**
     * The apps [mode] is about — exceptions when excluding, targets when
     * including. See [ScheduleBlock.packages].
     */
    val packages: Set<String> = emptySet(),
    /**
     * Absent from every schedule saved before this existed, and defaulted here
     * rather than migrated. Exclusive is what those schedules meant, so the
     * default *is* the migration; rewriting them would only be a chance to get
     * one wrong.
     */
    val mode: ScheduleBlock.Mode = ScheduleBlock.Mode.EXCLUSIVE,
    val enabled: Boolean = true,
    /**
     * [ScheduleBlock.Kind.BEDTIME] for the one fixed-id entry [ScheduleStore]
     * treats specially — see its own doc comment — [ScheduleBlock.Kind.CUSTOM]
     * for everything a family names themselves. Absent from every schedule
     * saved before this field existed, and defaulted here rather than
     * migrated, the same way [mode] already is: every schedule that predates
     * this was somebody's custom window, never bedtime.
     */
    val kind: ScheduleBlock.Kind = ScheduleBlock.Kind.CUSTOM,
    /** See [ScheduleBlock.parityAnchor] — absent here means every week, same as there. */
    val parityAnchorEpochDay: Long? = null,
) {
    val start: LocalTime get() = startMinuteOfDay.toLocalTime()
    val end: LocalTime get() = endMinuteOfDay.toLocalTime()
    val parityAnchor: LocalDate? get() = parityAnchorEpochDay?.let(LocalDate::ofEpochDay)

    /** Whether the engine would accept this. The editor refuses to save otherwise. */
    val isComplete: Boolean
        get() = name.isNotBlank() &&
            days.isNotEmpty() &&
            startMinuteOfDay != endMinuteOfDay &&
            // An including window with nothing to include pauses nothing at all.
            // Excluding with an empty set is the opposite and entirely valid: it
            // is bedtime, and it is the commonest schedule there is.
            !(mode == ScheduleBlock.Mode.INCLUSIVE && packages.isEmpty())

    /**
     * Null when incomplete, so one unfinished row cannot break the whole policy —
     * also null if every stored [days] value turns out not to be a real ISO day
     * number, which the same "don't break the policy" reasoning applies to.
     */
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
            packages = packages.map(::PackageName).toSet(),
            mode = mode,
            kind = kind,
            parityAnchor = parityAnchor,
        )
    }

}

/**
 * The family's named schedule windows — the storage format #105/#106
 * migrated away from for anything a parent can still reach, kept alive here
 * only for [LocalPolicyStore]'s one-time migration, sync/replication of a
 * schedule to or from a sibling phone still on an older build, and this
 * store's own dedicated tests.
 *
 * Bedtime lives here too, as the one entry at [BEDTIME_ID] with
 * [ScheduleBlock.Kind.BEDTIME] — [dev.schrammel.protego_parentis.model.Policy]
 * already treated a replicated schedule and the synthesised bedtime block as
 * the same kind of object once assembled (#12), and this made that true of
 * storage too, closing the gap #41 opened it for: only a replicated schedule
 * could be set from a parent's own phone, so bedtime — the most-used remote
 * control in the product — could not.
 *
 * [BEDTIME_ID] no longer needs the special treatment that history left it —
 * the unified rules screen (#106) offers bedtime as an ordinary starter like
 * any other window, generic add/delete flow included, since a family with no
 * bedtime rule yet cannot accidentally delete one either way. A migrated
 * pre-#105 bedtime schedule still lands at the same well-known rule id that
 * starter uses (`LocalPolicyStore.MIGRATED_SCHEDULE_ID_PREFIX + BEDTIME_ID`),
 * so the two paths describe the same rule rather than two.
 */
class ScheduleStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var schedules: List<StoredSchedule>
        get() = decode(prefs.getString(KEY_SCHEDULES, null))
        set(value) = prefs.edit { putString(KEY_SCHEDULES, encode(value)) }

    fun find(id: String): StoredSchedule? = schedules.firstOrNull { it.id == id }

    fun upsert(schedule: StoredSchedule) = synchronized(prefs) {
        schedules = schedules.filterNot { it.id == schedule.id } + schedule
    }

    fun delete(id: String) = synchronized(prefs) {
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
                    put(KEY_PACKAGES, JSONArray(schedule.packages.sorted()))
                    put(KEY_MODE, schedule.mode.name)
                    put(KEY_ENABLED, schedule.enabled)
                    put(KEY_KIND, schedule.kind.name)
                    schedule.parityAnchorEpochDay?.let { put(KEY_PARITY_ANCHOR, it) }
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
            packages = json.optJSONArray(KEY_PACKAGES).toStringSet(),
            // Anything unrecognised reads as exclusive, which is both the old
            // behaviour and the safer of the two: it pauses more, not less.
            mode = ScheduleBlock.Mode.entries
                .firstOrNull { it.name == json.optString(KEY_MODE) }
                ?: ScheduleBlock.Mode.EXCLUSIVE,
            enabled = json.optBoolean(KEY_ENABLED, true),
            // Anything unrecognised, including absent, reads as custom — every
            // schedule saved before this field existed was somebody's own
            // window, never bedtime.
            kind = ScheduleBlock.Kind.entries
                .firstOrNull { it.name == json.optString(KEY_KIND) }
                ?: ScheduleBlock.Kind.CUSTOM,
            parityAnchorEpochDay = json.takeIf { it.has(KEY_PARITY_ANCHOR) }?.optLong(KEY_PARITY_ANCHOR),
        )
    }

    private fun JSONArray?.toIntSet(): Set<Int> =
        this?.let { array -> (0 until array.length()).map(array::optInt).toSet() }.orEmpty()

    private fun JSONArray?.toStringSet(): Set<String> =
        this?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
        }.orEmpty().toSet()

    companion object {
        /** The one schedule id [ScheduleStore] and its screens treat specially. */
        const val BEDTIME_ID = "bedtime"

        private const val FILE_NAME = "schedules"
        private const val KEY_SCHEDULES = "schedules"

        private const val KEY_ID = "id"
        private const val KEY_NAME = "name"
        private const val KEY_DAYS = "days"
        private const val KEY_START = "start"
        private const val KEY_END = "end"
        /**
         * Still "allowed", not "packages". The key names what was written by
         * every version that shipped before modes existed, and renaming it would
         * silently empty the exception list of every schedule already on a phone.
         */
        private const val KEY_PACKAGES = "allowed"
        private const val KEY_MODE = "mode"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_KIND = "kind"
        private const val KEY_PARITY_ANCHOR = "parityAnchorEpochDay"
    }
}
