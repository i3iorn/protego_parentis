package dev.schrammel.protego_parentis.sync

import android.content.Context
import androidx.core.content.edit
import dev.schrammel.protego_parentis.sync.core.DeviceId
import java.time.Instant

/**
 * A parent phone's cached copy of a child's diagnostic log — the same
 * request/cache shape [AppInventoryStore] already uses for the app list, one
 * per managed phone (a family can hold more than one).
 *
 * Read-only from this phone's point of view, same as [AppInventoryStore]:
 * nothing here changes what the child's phone does, it only remembers the
 * last answer to a [SyncPublisher.publishDiagnosticsRequest] so a parent can
 * ask "what has this phone been doing" without holding it.
 */
class RemoteDiagnosticsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /**
     * [childId]'s log, in the same wire encoding
     * [dev.schrammel.protego_parentis.diagnostics.Diagnostics.raw] produced
     * it in — null if that phone has never answered.
     */
    fun log(childId: DeviceId): String? = prefs.getString(KEY_LOG_PREFIX + childId.value, null)

    fun setLog(childId: DeviceId, raw: String) = prefs.edit {
        putString(KEY_LOG_PREFIX + childId.value, raw)
        putLong(KEY_RECEIVED_AT_PREFIX + childId.value, Instant.now().toEpochMilli())
    }

    /** Null until this phone has ever heard back from [childId]. */
    fun receivedAt(childId: DeviceId): Instant? =
        prefs.getLong(KEY_RECEIVED_AT_PREFIX + childId.value, 0L).takeIf { it > 0L }?.let(Instant::ofEpochMilli)

    private companion object {
        const val FILE_NAME = "remote_diagnostics"
        const val KEY_LOG_PREFIX = "log/"
        const val KEY_RECEIVED_AT_PREFIX = "receivedAt/"
    }
}
