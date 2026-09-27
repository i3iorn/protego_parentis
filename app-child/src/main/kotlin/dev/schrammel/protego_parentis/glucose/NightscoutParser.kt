package dev.schrammel.protego_parentis.glucose

import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.GlucoseReading
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Reads the two Nightscout responses the app uses. Pure, so it is tested
 * without a server; anything unexpected reads as "no reading" rather than a
 * wrong one.
 */
internal object NightscoutParser {

    /**
     * The newest sensor glucose value in a `/api/v1/entries/sgv.json`
     * response. Entries without a plausible `sgv` or a `date` are skipped:
     * calibrations and meter readings share the endpoint on some uploaders.
     */
    fun latestReading(body: String): GlucoseReading? {
        val entries = runCatching { JSONArray(body) }.getOrNull() ?: return null
        return (0 until entries.length())
            .mapNotNull { entries.optJSONObject(it)?.let(::reading) }
            .maxByOrNull { it.at }
    }

    private fun reading(entry: JSONObject): GlucoseReading? {
        val type = entry.optString("type")
        if (type.isNotEmpty() && type != "sgv") return null
        val sgv = entry.optInt("sgv", -1).takeIf { it in Condition.GLUCOSE_RANGE } ?: return null
        val date = entry.optLong("date", 0L).takeIf { it > 0 } ?: return null
        return GlucoseReading(sgv, Instant.ofEpochMilli(date))
    }

    /** The display unit from a `/api/v1/status.json` response, or null if it doesn't say. */
    fun units(body: String): GlucoseUnit? {
        val status = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return GlucoseUnit.fromNightscout(status.optJSONObject("settings")?.optString("units"))
    }
}
