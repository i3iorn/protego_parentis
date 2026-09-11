package dev.schrammel.protego_parentis.context

import android.content.Context
import android.os.BatteryManager
import android.util.Log

/**
 * The one place `BatteryManager` is actually read from — see [percent].
 *
 * Public rather than `internal` because `enforcement.EnforcementService` calls
 * it directly: `internal` is module-scoped, and once `context` moved into its
 * own Gradle module (Phase 3), a same-module modifier could no longer serve a
 * cross-module call site — the same problem `security.LocalDeviceId.useInTests`
 * hit when `:security` was extracted.
 */
object BatteryReader {

    /** Battery percentage, or null if it could not be read. No permission needed. */
    fun percent(context: Context): Int? = runCatching {
        context.applicationContext.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }
    }.getOrElse {
        Log.d("DementorContext", "Could not read the battery level", it)
        null
    }
}
