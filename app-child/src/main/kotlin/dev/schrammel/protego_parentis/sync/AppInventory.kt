package dev.schrammel.protego_parentis.sync

import android.content.Context
import androidx.core.content.edit
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.usage.InstalledApps
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
 * A parent phone's cached copy of a child's app list, one per managed phone
 * (#43 — a family can hold more than one).
 *
 * Read-only from this phone's point of view — nothing here installs, removes or
 * renames anything, it only remembers what the family's phones have said about
 * "what's on that phone" so a rule screen has something to offer without
 * waiting on the network every time it opens. Usually the last full answer to
 * an [AppPickerActivity] request, kept current between those by [upsert]
 * folding in each new-app notice as it arrives.
 */
class AppInventoryStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun apps(childId: DeviceId): List<AppInventoryEntry> =
        AppInventoryCodec.decode(prefs.getString(KEY_APPS_PREFIX + childId.value, null))

    fun setApps(childId: DeviceId, apps: List<AppInventoryEntry>) = prefs.edit {
        putString(KEY_APPS_PREFIX + childId.value, AppInventoryCodec.encode(apps))
        putLong(KEY_RECEIVED_AT_PREFIX + childId.value, Instant.now().toEpochMilli())
    }

    /**
     * Adds or replaces one app in [childId]'s cached list, keeping the rest.
     *
     * For a new-app notice arriving between full syncs — see
     * [dev.schrammel.protego_parentis.sync.SyncReceiver]'s own call site.
     * Deliberately not exposed as a way to *build* the inventory from
     * scratch: call only once [apps] has already returned something from a
     * real full sync, or a single install notice would look like the whole
     * inventory to a picker that has never asked for one yet.
     */
    fun upsert(childId: DeviceId, entry: AppInventoryEntry) =
        setApps(childId, apps(childId).filterNot { it.packageName == entry.packageName } + entry)

    /** Null until this phone has ever heard back from [childId]. */
    fun receivedAt(childId: DeviceId): Instant? =
        prefs.getLong(KEY_RECEIVED_AT_PREFIX + childId.value, 0L).takeIf { it > 0L }?.let(Instant::ofEpochMilli)

    private companion object {
        const val FILE_NAME = "app_inventory"
        const val KEY_APPS_PREFIX = "apps/"
        const val KEY_RECEIVED_AT_PREFIX = "receivedAt/"
    }
}
