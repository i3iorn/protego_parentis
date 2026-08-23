package dev.schrammel.dementor_watch.rules

import android.content.Context
import androidx.core.content.edit

/**
 * Which apps a parent has already seen (AC-3).
 *
 * The feature is "tell me when something new appears", and that needs a memory of
 * what was already there. Without one, the first launch after installing this app
 * would report every app on the phone as new, and the parent would learn on day
 * one that the notification is noise.
 *
 * So the first sweep *seeds* rather than reports. That is a deliberate trade: an
 * app installed in the hour before setup is missed, which is a much better failure
 * than sixty false alarms.
 */
class AppReviewStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** False until the first sweep has established a baseline. */
    val isSeeded: Boolean get() = prefs.getBoolean(KEY_SEEDED, false)

    /** Everything the family has either seeded or reviewed. */
    var known: Set<String>
        get() = prefs.getStringSet(KEY_KNOWN, emptySet()).orEmpty()
        private set(value) = prefs.edit { putStringSet(KEY_KNOWN, value) }

    /** Seen but not yet allowed or blocked. */
    var pending: Set<String>
        get() = prefs.getStringSet(KEY_PENDING, emptySet()).orEmpty()
        private set(value) = prefs.edit { putStringSet(KEY_PENDING, value) }

    /**
     * Establishes the baseline on first run. Everything present now counts as known
     * and nothing is reported.
     */
    fun seed(installed: Set<String>) {
        prefs.edit {
            putStringSet(KEY_KNOWN, installed)
            putStringSet(KEY_PENDING, emptySet())
            putBoolean(KEY_SEEDED, true)
        }
    }

    /**
     * Records [installed] and returns the packages that are genuinely new.
     *
     * Called both from the install broadcast and from the enforcement tick — the
     * broadcast is the fast path, the sweep catches anything installed while the
     * service was not running, which on a rebooting phone is a real window.
     */
    fun noticeInstalled(installed: Set<String>): Set<String> {
        if (!isSeeded) {
            seed(installed)
            return emptySet()
        }

        val fresh = installed - known
        if (fresh.isEmpty()) return emptySet()

        prefs.edit {
            putStringSet(KEY_KNOWN, known + fresh)
            putStringSet(KEY_PENDING, pending + fresh)
        }
        return fresh
    }

    /** Clears the "new" flag once someone has said yes or no to it. */
    fun markReviewed(pkg: String) {
        prefs.edit {
            putStringSet(KEY_PENDING, pending - pkg)
            putStringSet(KEY_KNOWN, known + pkg)
        }
    }

    /**
     * Forgets an app that is no longer installed.
     *
     * Left in [known] on purpose: an app that is removed and reinstalled is not
     * news, and reporting it as new every time would turn a reinstall loop into a
     * notification loop.
     */
    fun forgetPending(pkg: String) {
        prefs.edit { putStringSet(KEY_PENDING, pending - pkg) }
    }

    private companion object {
        const val FILE_NAME = "app_review"
        const val KEY_KNOWN = "known"
        const val KEY_PENDING = "pending"
        const val KEY_SEEDED = "seeded"
    }
}
