package dev.schrammel.dementor_watch.rules

import android.content.Context
import androidx.core.content.edit
import dev.schrammel.dementor_watch.model.CategoryId
import dev.schrammel.dementor_watch.model.Grant
import dev.schrammel.dementor_watch.model.GrantId
import dev.schrammel.dementor_watch.model.GrantTarget
import dev.schrammel.dementor_watch.model.PackageName
import dev.schrammel.dementor_watch.policy.PolicyClock
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

/**
 * Bonus time granted by a parent.
 *
 * Grants never modify the underlying limit — they sit alongside it and expire at
 * the next day boundary. That separation is what lets a report distinguish a week
 * that genuinely went well from one that was topped up every evening.
 */
class GrantStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** Grants that have not yet expired. Expired ones are dropped on read. */
    fun active(now: Instant): List<Grant> =
        all().filter { it.isActiveAt(now) }

    fun add(
        target: GrantTarget,
        minutes: Int,
        now: Instant,
        zone: ZoneId,
        dayBoundaryHour: Int,
    ) {
        val day = PolicyClock.policyDay(now, zone, dayBoundaryHour)
        val grant = Grant(
            id = GrantId(UUID.randomUUID().toString()),
            target = target,
            amount = minutes.minutes,
            grantedAt = now,
            // Bonus time is for today only; tomorrow starts from the normal limit.
            expiresAt = PolicyClock.dayEnd(day, zone, dayBoundaryHour),
            grantedBy = GRANTED_BY_LOCAL,
        )
        write(all().filter { it.isActiveAt(now) } + grant)
    }

    /** Drops every grant for today — the "I changed my mind" path. */
    fun clearAll() {
        write(emptyList())
    }

    private fun all(): List<Grant> {
        val raw = prefs.getString(KEY_GRANTS, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let(::fromJson)
        }
    }

    private fun write(grants: List<Grant>) {
        val array = JSONArray(grants.map(::toJson))
        prefs.edit { putString(KEY_GRANTS, array.toString()) }
    }

    private fun toJson(grant: Grant): JSONObject = JSONObject().apply {
        put(KEY_ID, grant.id.value)
        put(KEY_MINUTES, grant.amount.inWholeMinutes)
        put(KEY_GRANTED_AT, grant.grantedAt.toEpochMilli())
        put(KEY_EXPIRES_AT, grant.expiresAt.toEpochMilli())
        when (val target = grant.target) {
            is GrantTarget.Device -> put(KEY_TARGET, TARGET_DEVICE)
            is GrantTarget.App -> {
                put(KEY_TARGET, TARGET_APP)
                put(KEY_TARGET_ID, target.pkg.value)
            }

            is GrantTarget.Category -> {
                put(KEY_TARGET, TARGET_CATEGORY)
                put(KEY_TARGET_ID, target.id.value)
            }
        }
    }

    private fun fromJson(json: JSONObject): Grant? {
        val minutes = json.optLong(KEY_MINUTES, 0L)
        if (minutes <= 0L) return null
        val targetId = json.optString(KEY_TARGET_ID)
        val target = when (json.optString(KEY_TARGET)) {
            TARGET_DEVICE -> GrantTarget.Device
            TARGET_APP -> targetId.takeIf(String::isNotBlank)
                ?.let { GrantTarget.App(PackageName(it)) }

            TARGET_CATEGORY -> targetId.takeIf(String::isNotBlank)
                ?.let { GrantTarget.Category(CategoryId(it)) }

            else -> null
        } ?: return null

        val grantedAt = json.optLong(KEY_GRANTED_AT, 0L)
        val expiresAt = json.optLong(KEY_EXPIRES_AT, 0L)
        if (expiresAt <= grantedAt) return null

        return Grant(
            id = GrantId(json.optString(KEY_ID).ifBlank { UUID.randomUUID().toString() }),
            target = target,
            amount = minutes.toInt().minutes,
            grantedAt = Instant.ofEpochMilli(grantedAt),
            expiresAt = Instant.ofEpochMilli(expiresAt),
            grantedBy = GRANTED_BY_LOCAL,
        )
    }

    private companion object {
        const val FILE_NAME = "grants"
        const val KEY_GRANTS = "grants"
        const val KEY_ID = "id"
        const val KEY_MINUTES = "minutes"
        const val KEY_GRANTED_AT = "grantedAt"
        const val KEY_EXPIRES_AT = "expiresAt"
        const val KEY_TARGET = "target"
        const val KEY_TARGET_ID = "targetId"
        const val TARGET_DEVICE = "device"
        const val TARGET_APP = "app"
        const val TARGET_CATEGORY = "category"

        /** Until the server exists, every grant comes from the person holding the PIN. */
        const val GRANTED_BY_LOCAL = "local-parent"
    }
}
