package dev.schrammel.protego_parentis.enforcement

import android.content.Context
import androidx.core.content.edit

/**
 * What the last enforcement pass found, for screens to read.
 *
 * Exists so that showing a status does not require producing one. The rules
 * screen wants to say whether the filtering resolver was accepted, and getting
 * that by running a fresh evaluation would mean opening a settings screen drove
 * `DevicePolicyManager` — suspending and unsuspending apps as a side effect of
 * someone glancing at a label.
 *
 * Deliberately tiny. Anything that belongs in the shared history goes to `Audit`,
 * and anything that is a count goes to `Diagnostics`; this holds only the handful
 * of facts a screen needs and cannot cheaply recompute.
 */
class EnforcementStatus(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /**
     * Whether the device refused the configured filtering resolver (WF-1).
     *
     * The one web-filtering failure that is otherwise invisible: nothing changes
     * on the phone, and the settings screen goes on describing a filter that is
     * not running.
     */
    var dnsFailed: Boolean
        get() = prefs.getBoolean(KEY_DNS_FAILED, false)
        set(value) {
            // Written on every pass, so only touch storage when the answer moves.
            if (dnsFailed == value) return
            prefs.edit { putBoolean(KEY_DNS_FAILED, value) }
        }

    private companion object {
        const val FILE_NAME = "enforcement_status"
        const val KEY_DNS_FAILED = "dns_failed"
    }
}
