package dev.schrammel.protego_parentis.rules

import android.content.Context
import androidx.core.content.edit
import dev.schrammel.protego_parentis.model.BrowserPolicy
import dev.schrammel.protego_parentis.model.DnsFilter

/**
 * One of the filtering resolvers a family can choose from.
 *
 * The spec's plan was a resolver with a per-family profile whose categories a
 * parent toggles, bought from a provider. There is no account to buy one with
 * yet, so the honest version is a short list of public resolvers that filter
 * today. Categories are whatever the chosen resolver blocks, and the screen says
 * so rather than offering switches that do nothing.
 */
enum class DnsProfile(val host: String?) {
    /** No filtering; the device uses whatever resolver the network offers. */
    OFF(null),

    /** Cloudflare for Families: malware. */
    MALWARE("security.cloudflare-dns.com"),

    /** Cloudflare for Families: malware and adult content. */
    ADULT("family.cloudflare-dns.com"),

    /** AdGuard's family profile: adult content plus advertising and trackers. */
    STRICT("family.adguard-dns.com"),

    /** A hostname the family typed themselves. */
    CUSTOM(null),
    ;

    companion object {
        fun parse(value: String?): DnsProfile =
            entries.firstOrNull { it.name == value } ?: OFF
    }
}

/**
 * The family's web filtering settings (WF-1, WF-2).
 *
 * Kept apart from [LocalPolicyStore] because it is a distinct decision with a
 * distinct screen, and because the DNS host is the one setting here whose failure
 * mode is silent — a phone can be pointed at a resolver that does not answer and
 * look exactly like one that is filtering.
 */
class WebFilterStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var profile: DnsProfile
        get() = DnsProfile.parse(prefs.getString(KEY_PROFILE, null))
        set(value) = prefs.edit { putString(KEY_PROFILE, value.name) }

    /** Only used when [profile] is [DnsProfile.CUSTOM]. */
    var customHost: String
        get() = prefs.getString(KEY_CUSTOM_HOST, null).orEmpty()
        set(value) = prefs.edit { putString(KEY_CUSTOM_HOST, value.trim()) }

    /**
     * The filter to apply, or null for none.
     *
     * Null rather than a throw when a custom hostname is unusable: the editor
     * refuses to save one, so reaching here means a stored value from an older
     * build or a hand-edited file, and no filtering is a safer reading than a
     * crash on every evaluation.
     */
    fun filter(): DnsFilter? {
        val host = when (val chosen = profile) {
            DnsProfile.OFF -> null
            DnsProfile.CUSTOM -> customHost.takeIf(::isPlausibleHost)
            else -> chosen.host
        } ?: return null

        return runCatching { DnsFilter(host) }.getOrNull()
    }

    /**
     * Whether to count the sites this phone reaches (WF-3).
     *
     * Off until a parent turns it on. Android shows the person holding the phone a
     * permanent notice while it is on, which is not an obstacle to work around —
     * it is the only thing that makes counting acceptable.
     */
    var countVisits: Boolean
        get() = prefs.getBoolean(KEY_COUNT_VISITS, false)
        set(value) = prefs.edit { putBoolean(KEY_COUNT_VISITS, value) }

    var browserPolicy: BrowserPolicy
        get() = BrowserPolicy(
            forceSafeSearch = prefs.getBoolean(KEY_SAFE_SEARCH, false),
            restrictYouTube = prefs.getBoolean(KEY_YOUTUBE, false),
            blockIncognito = prefs.getBoolean(KEY_INCOGNITO, false),
            blockedUrls = prefs.getString(KEY_BLOCKED, null).toLines(),
            allowedUrls = prefs.getString(KEY_ALLOWED, null).toLines(),
            suspendUnmanagedBrowsers = prefs.getBoolean(KEY_SUSPEND_BROWSERS, false),
        )
        set(value) = prefs.edit {
            putBoolean(KEY_SAFE_SEARCH, value.forceSafeSearch)
            putBoolean(KEY_YOUTUBE, value.restrictYouTube)
            putBoolean(KEY_INCOGNITO, value.blockIncognito)
            putString(KEY_BLOCKED, value.blockedUrls.joinToString("\n"))
            putString(KEY_ALLOWED, value.allowedUrls.joinToString("\n"))
            putBoolean(KEY_SUSPEND_BROWSERS, value.suspendUnmanagedBrowsers)
        }

    private fun String?.toLines(): List<String> =
        this?.split('\n')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()

    private companion object {
        const val FILE_NAME = "web_filter"

        const val KEY_PROFILE = "profile"
        const val KEY_CUSTOM_HOST = "custom_host"
        const val KEY_SAFE_SEARCH = "safe_search"
        const val KEY_YOUTUBE = "youtube"
        const val KEY_INCOGNITO = "incognito"
        const val KEY_BLOCKED = "blocked"
        const val KEY_ALLOWED = "allowed"
        const val KEY_SUSPEND_BROWSERS = "suspend_browsers"
        const val KEY_COUNT_VISITS = "count_visits"
    }
}

/**
 * A cheap sanity check on a typed hostname.
 *
 * Not validation — only Android can say whether a host answers DNS-over-TLS, and
 * it tells us by refusing. This catches the mistake people actually make, which
 * is pasting a URL into a field that wants a hostname.
 */
internal fun isPlausibleHost(value: String): Boolean {
    val host = value.trim()
    return host.isNotEmpty() &&
        host.length <= MAX_HOST_LENGTH &&
        host.contains('.') &&
        !host.contains(' ') &&
        !host.contains('/') &&
        !host.contains(':')
}

private const val MAX_HOST_LENGTH = 253
