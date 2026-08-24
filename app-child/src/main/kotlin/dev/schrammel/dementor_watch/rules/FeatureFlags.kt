package dev.schrammel.dementor_watch.rules

import android.content.Context
import androidx.core.content.edit

/**
 * A part of the app a family can put away.
 *
 * This exists because the settings have grown past what any one family uses. A
 * household that wants a daily limit and a bedtime should not have to scroll past
 * contextual rules, geofences and DNS profiles to find them — and a child looking
 * at their own phone should not see a screen implying they are being tracked when
 * nobody has turned tracking on.
 *
 * Three are not offered, and it is worth saying why rather than quietly omitting
 * them. The emergency button is a safety floor; the spec's own principle is that
 * safety overrides everything, and a safety feature that a settings screen can
 * remove is not a floor. The shared history and the what-is-shared screen are the
 * transparency promise: an app that can be configured to stop showing the person
 * being monitored what is collected has become a different product, whatever it
 * says on the tin.
 */
enum class Feature(
    /** False when the product would stop being itself without it. */
    val optional: Boolean = true,
) {
    /** Asking for more time, and answering. */
    REQUESTS,

    /** Standing answers to those asks. */
    AUTO_APPROVE,

    /** Extra time, given without being asked. */
    BONUS,

    /** Groups of apps with a shared allowance. */
    CATEGORIES,

    /** A limit for one app on its own. */
    APP_LIMITS,

    /** Named windows: school, dinner, homework. */
    SCHEDULES,

    /** Rules that depend on where the phone is or what it is near. */
    CONTEXT_RULES,

    /** Saved places, and being told about arriving at them. */
    PLACES,

    /** Something to earn by staying under a target. */
    GOALS,

    /** Blocking apps, and taking their internet away. */
    APP_CONTROL,

    /** The filtering resolver and the browser settings. */
    WEB_FILTER,

    /** Counting the sites the phone reaches. */
    WEB_ACTIVITY,

    /** Asking where the phone is. */
    LOCATION,

    /** Messages between the phones in the family. */
    CHAT,

    // ---------- not offered ----------

    /** The emergency button. Safety overrides everything, including tidiness. */
    EMERGENCY(optional = false),

    /** The shared history. Half the reason a child should accept any of this. */
    HISTORY(optional = false),

    /** The screen that says what is collected. Same reason. */
    TRANSPARENCY(optional = false),
}

/**
 * Which parts of the app this family uses.
 *
 * Everything on by default: a family discovers what they need by seeing it, and a
 * product that starts empty asks people to know what they want before they have
 * used it.
 *
 * **Turning something off hides its screens; it does not undo it.** A schedule
 * already written goes on running, because silently lifting a restriction from a
 * tidying-up screen is the opposite of what a parent tidying up expects — and
 * because the alternative, deleting their work, is worse. The screen says so.
 */
class FeatureFlags(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun isEnabled(feature: Feature): Boolean {
        if (!feature.optional) return true
        return prefs.getBoolean(feature.name, true)
    }

    /** Refuses the three that are not offered, rather than storing a lie. */
    fun setEnabled(feature: Feature, enabled: Boolean) {
        if (!feature.optional) return
        prefs.edit { putBoolean(feature.name, enabled) }
    }

    private companion object {
        const val FILE_NAME = "features"
    }
}
