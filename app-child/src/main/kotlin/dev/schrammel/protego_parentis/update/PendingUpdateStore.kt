package dev.schrammel.protego_parentis.update

import android.content.Context
import androidx.core.content.edit

/** A verified update, downloaded by [UpdateCheckWorker], and its version name. */
data class PendingUpdate(val apkPath: String, val versionName: String)

/**
 * What [UpdateCheckWorker] downloaded but could not install unattended.
 *
 * Only ever written on a phone without silent-install privilege — see
 * [ApkInstaller]'s own doc comment — so the diagnostics screen has something
 * to offer an "Install now" button for, and the notification that told the
 * parent about it has something to reopen.
 */
class PendingUpdateStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var pending: PendingUpdate?
        get() {
            val path = prefs.getString(KEY_PATH, null) ?: return null
            val versionName = prefs.getString(KEY_VERSION_NAME, null) ?: return null
            return PendingUpdate(path, versionName)
        }
        set(value) = prefs.edit {
            if (value == null) {
                remove(KEY_PATH)
                remove(KEY_VERSION_NAME)
            } else {
                putString(KEY_PATH, value.apkPath)
                putString(KEY_VERSION_NAME, value.versionName)
            }
        }

    private companion object {
        const val FILE_NAME = "pending_update"
        const val KEY_PATH = "path"
        const val KEY_VERSION_NAME = "versionName"
    }
}
