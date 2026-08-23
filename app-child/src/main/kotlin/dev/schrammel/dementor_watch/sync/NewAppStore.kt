package dev.schrammel.dementor_watch.sync

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** A new app on the child's phone, as a parent phone hears about it (AC-3). */
data class NewApp(
    val packageName: String,
    /** The label the child's phone sent; a parent phone cannot look one up. */
    val label: String,
    val noticedAt: Instant,
)

/**
 * New apps a parent phone has been told about but not yet answered.
 *
 * An answer removes the row. Nothing accumulates here, because the useful
 * question is "is there anything to look at", not "what has ever been installed"
 * — the latter is the child's own app list, which the child can see and the
 * parent, deliberately, cannot.
 */
class NewAppStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun all(): List<NewApp> = decode(prefs.getString(KEY_APPS, null))
        .sortedByDescending { it.noticedAt }

    /**
     * Ignores a package already waiting, so a repeated message is not a repeated row.
     *
     * Locked for the same reason the inbox is: a poll arriving while a parent taps
     * Allow would otherwise lose one of the two writes.
     */
    fun remember(app: NewApp) = synchronized(LOCK) {
        val existing = decode(prefs.getString(KEY_APPS, null))
        if (existing.none { it.packageName == app.packageName }) {
            write(existing + app)
        }
    }

    fun forget(packageName: String) = synchronized(LOCK) {
        write(decode(prefs.getString(KEY_APPS, null)).filterNot { it.packageName == packageName })
    }

    private fun write(apps: List<NewApp>) {
        prefs.edit {
            putString(
                KEY_APPS,
                JSONArray(
                    apps.takeLast(MAX_ENTRIES).map { app ->
                        JSONObject().apply {
                            put(KEY_PACKAGE, app.packageName)
                            put(KEY_LABEL, app.label)
                            put(KEY_AT, app.noticedAt.toEpochMilli())
                        }
                    },
                ).toString(),
            )
        }
    }

    private fun decode(raw: String?): List<NewApp> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val json = array.optJSONObject(index) ?: return@mapNotNull null
            val pkg = json.optString(KEY_PACKAGE).takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            NewApp(
                packageName = pkg,
                label = json.optString(KEY_LABEL).ifBlank { pkg },
                noticedAt = Instant.ofEpochMilli(json.optLong(KEY_AT, 0L)),
            )
        }
    }

    private companion object {
        /** Shared across instances: every screen constructs its own store. */
        val LOCK = Any()

        const val FILE_NAME = "new_apps"
        const val KEY_APPS = "apps"

        /** A bound, not a policy: nobody reviews a hundred apps in one sitting. */
        const val MAX_ENTRIES = 50

        const val KEY_PACKAGE = "package"
        const val KEY_LABEL = "label"
        const val KEY_AT = "at"
    }
}
