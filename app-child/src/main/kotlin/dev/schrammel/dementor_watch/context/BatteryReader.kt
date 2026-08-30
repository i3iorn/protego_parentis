package dev.schrammel.dementor_watch.context

import android.content.Context
import android.os.BatteryManager
import android.util.Log

/** The one place `BatteryManager` is actually read from — see [percent]. */
internal object BatteryReader {

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
