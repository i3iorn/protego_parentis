package dev.schrammel.dementor_watch.sync

import android.content.Context
import androidx.core.content.edit
import dev.schrammel.dementor_watch.usage.InstalledApps
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** One app, as a parent's phone is told about it: a name, never an icon or usage. */
data class AppInventoryEntry(val packageName: String, val label: String)

/** Encoding for [AppInventoryEntry] lists, carried as one message field. */
object AppInventoryCodec {
    private const val KEY_PACKAGE = "p"
    private const val KEY_LABEL = "l"

    fun encode(apps: List<AppInventoryEntry>): String =
        JSONArray(
            apps.map { app ->
                JSONObject().apply {
                    put(KEY_PACKAGE, app.packageName)
                    put(KEY_LABEL, app.label)
                }
            },
        ).toString()

    fun decode(raw: String?): List<AppInventoryEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val pkg = obj.optString(KEY_PACKAGE).takeIf(String::isNotBlank) ?: return@mapNotNull null
            AppInventoryEntry(pkg, obj.optString(KEY_LABEL).ifBlank { pkg })
        }
    }
}

/** What this phone can currently launch, in the shape [APP_INVENTORY] carries. */
fun installedInventory(context: Context): List<AppInventoryEntry> =
    InstalledApps(context).launchable().map { AppInventoryEntry(it.pkg.value, it.label) }

/**
 * A parent phone's cached copy of the child's app list.
 *
 * Read-only from this phone's point of view — nothing here installs, removes or
 * renames anything, it only remembers the last answer to "what's on that phone"
 * so a rule screen has something to offer without waiting on the network every
 * time it opens.
 */
class AppInventoryStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var apps: List<AppInventoryEntry>
        get() = AppInventoryCodec.decode(prefs.getString(KEY_APPS, null))
        set(value) = prefs.edit {
            putString(KEY_APPS, AppInventoryCodec.encode(value))
            putLong(KEY_RECEIVED_AT, Instant.now().toEpochMilli())
        }

    /** Null until this phone has ever heard back. */
    val receivedAt: Instant?
        get() = prefs.getLong(KEY_RECEIVED_AT, 0L).takeIf { it > 0L }?.let(Instant::ofEpochMilli)

    private companion object {
        const val FILE_NAME = "app_inventory"
        const val KEY_APPS = "apps"
        const val KEY_RECEIVED_AT = "receivedAt"
    }
}
