package dev.schrammel.dementor_watch.rules

import android.content.Context
import androidx.core.content.edit
import dev.schrammel.dementor_watch.model.AutoApproval
import dev.schrammel.dementor_watch.model.AutoApprovalWindow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.time.Duration.Companion.minutes

/**
 * The family's standing answers to requests (RQ-1).
 *
 * Written by a parent behind the PIN, read by the request coordinator on the
 * child's phone. Parsed defensively: a row that will not read is dropped rather
 * than thrown, because this is loaded on the phone that has to keep working.
 *
 * There is no default rule and setup creates none. The whole value of the feature
 * is that a parent chose it; a pre-enabled one would be the app handing out time
 * nobody asked it to.
 */
class AutoApprovalStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var rules: List<AutoApproval>
        get() = decode(prefs.getString(KEY_RULES, null))
        set(value) = prefs.edit { putString(KEY_RULES, encode(value)) }

    fun find(id: String): AutoApproval? = rules.firstOrNull { it.id == id }

    fun upsert(rule: AutoApproval) = synchronized(prefs) {
        rules = rules.filterNot { it.id == rule.id } + rule
    }

    fun delete(id: String) = synchronized(prefs) {
        rules = rules.filterNot { it.id == id }
    }

    /**
     * How many answers this phone has ever given without a person.
     *
     * Only ever incremented, and sent to the parents in the heartbeat. A count
     * that goes backwards means the record was rebuilt, which is the same
     * deterrent-by-visibility the shared history already relies on.
     */
    var firedTotal: Long
        get() = prefs.getLong(KEY_FIRED, 0L)
        private set(value) = prefs.edit { putLong(KEY_FIRED, value) }

    fun recordFired() = synchronized(prefs) {
        firedTotal += 1L
    }

    private fun encode(rules: List<AutoApproval>): String = JSONArray(
        rules.map { rule ->
            JSONObject().apply {
                put(KEY_ID, rule.id)
                put(KEY_NAME, rule.name)
                put(KEY_ENABLED, rule.enabled)
                put(KEY_MAX_MINUTES, rule.maxAmount.inWholeMinutes.toInt())
                put(KEY_MAX_PER_WINDOW, rule.maxPerWindow)
                put(KEY_WINDOW, rule.window.name)
            }
        },
    ).toString()

    private fun decode(raw: String?): List<AutoApproval> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()

        return (0 until array.length()).mapNotNull { index ->
            val json = array.optJSONObject(index) ?: return@mapNotNull null
            val id = json.optString(KEY_ID).takeIf(String::isNotBlank) ?: return@mapNotNull null
            val name = json.optString(KEY_NAME).takeIf(String::isNotBlank)
                ?: return@mapNotNull null

            // Clamped rather than trusted: the model refuses a rule that grants
            // nothing, and a hand-edited zero would otherwise take the whole list
            // down with it.
            val minutes = json.optInt(KEY_MAX_MINUTES, 0).coerceIn(1, MAX_MINUTES)
            val count = json.optInt(KEY_MAX_PER_WINDOW, 0).coerceIn(1, MAX_PER_WINDOW)

            runCatching {
                AutoApproval(
                    id = id,
                    name = name,
                    enabled = json.optBoolean(KEY_ENABLED, true),
                    maxAmount = minutes.minutes,
                    maxPerWindow = count,
                    window = AutoApprovalWindow.entries
                        .firstOrNull { it.name == json.optString(KEY_WINDOW) }
                        ?: AutoApprovalWindow.WEEK,
                )
            }.getOrNull()
        }
    }

    private companion object {
        const val FILE_NAME = "auto_approval"
        const val KEY_RULES = "rules"
        const val KEY_FIRED = "fired_total"

        /** An hour at a time is already a very generous standing yes. */
        const val MAX_MINUTES = 60
        const val MAX_PER_WINDOW = 20

        const val KEY_ID = "id"
        const val KEY_NAME = "name"
        const val KEY_ENABLED = "enabled"
        const val KEY_MAX_MINUTES = "maxMinutes"
        const val KEY_MAX_PER_WINDOW = "maxPerWindow"
        const val KEY_WINDOW = "window"
    }
}
