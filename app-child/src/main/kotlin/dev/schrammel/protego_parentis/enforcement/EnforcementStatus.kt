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

    /**
     * The rules that were applying on the last enforcement pass (rules-UX §B,
     * the "Active now" status).
     *
     * Stored rather than recomputed for the same reason [dnsFailed] is: the
     * rules list shows a chip on the cards that are applying right now, and
     * opening that screen to look must not re-collect a day of usage or re-run
     * a location read to draw it. A chip that is one pass behind an actual
     * change of state is the price, and it is the same price the device status
     * card already pays.
     */
    var activeRuleIds: Set<String>
        get() = prefs.getStringSet(KEY_ACTIVE_RULES, null).orEmpty()
        set(value) {
            if (activeRuleIds == value) return
            prefs.edit { putStringSet(KEY_ACTIVE_RULES, value) }
        }

    private companion object {
        const val FILE_NAME = "enforcement_status"
        const val KEY_DNS_FAILED = "dns_failed"
        const val KEY_ACTIVE_RULES = "active_rules"
    }
}
