package dev.schrammel.protego_parentis.model

/**
 * A filtering DNS resolver the whole device is pointed at (WF-1).
 *
 * Device-wide private DNS is the cheapest content filter Android offers: it costs
 * no battery, needs no VPN, and covers every app on the phone including WebViews
 * — Chrome defers its own secure DNS when Android's is configured. The trade is
 * that it is all-or-nothing across the device and can only block whole domains.
 *
 * The spec's plan was a resolver with a per-family profile, bought rather than
 * built. Until there is an account to buy one with, a family picks a public
 * filtering resolver instead, which is a real filter available today and honest
 * about what it does. Categories are whatever the chosen resolver blocks; this
 * app does not pretend to toggle them.
 */
data class DnsFilter(
    /** The DNS-over-TLS hostname, e.g. `family.cloudflare-dns.com`. */
    val host: String,
) {
    init {
        require(host.isNotBlank()) { "a DNS filter needs a hostname" }
        // A hostname, not a URL and not a line of text. Checked here rather than
        // trusted from the editor, because this value is handed straight to the
        // platform and a hostname nobody validated is a filter nobody has.
        require(host.none { it.isWhitespace() || it.isISOControl() }) {
            "a DNS hostname cannot contain whitespace or control characters"
        }
        require(!host.contains('/') && !host.contains(':')) {
            "private DNS takes a hostname, not a URL: '$host'"
        }
        require(host.length <= MAX_HOSTNAME_LENGTH) { "hostname is too long" }
    }

    private companion object {
        /** The DNS limit, and a bound on what can be pushed at the platform. */
        const val MAX_HOSTNAME_LENGTH = 253
    }
}

/**
 * What a managed browser is told to do (WF-2).
 *
 * Chrome honours enterprise managed configuration, so these arrive as application
 * restrictions rather than as anything this app enforces itself. That is the whole
 * appeal: no accessibility service, no overlay, no traffic interception.
 *
 * A browser that does *not* honour managed config is a hole straight through all
 * of it, which is what [suspendUnmanagedBrowsers] is for.
 */
data class BrowserPolicy(
    val forceSafeSearch: Boolean = false,
    /** YouTube's own restricted mode, applied through the same channel. */
    val restrictYouTube: Boolean = false,
    val blockIncognito: Boolean = false,
    val blockedUrls: List<String> = emptyList(),
    /**
     * Exceptions to [blockedUrls], which Chrome applies with higher precedence.
     * Blocking `*` and allowing a short list is how a family builds an allowlist.
     */
    val allowedUrls: List<String> = emptyList(),
    /**
     * Suspend browsers that cannot be managed.
     *
     * Off by default because it is a blunt instrument: it takes away a working
     * browser rather than filtering it, and the reason has to be explained to the
     * person holding the phone. On, it closes the obvious way round WF-2.
     */
    val suspendUnmanagedBrowsers: Boolean = false,
) {
    val isActive: Boolean
        get() = forceSafeSearch ||
            restrictYouTube ||
            blockIncognito ||
            blockedUrls.isNotEmpty() ||
            suspendUnmanagedBrowsers

    companion object {
        val OFF = BrowserPolicy()
    }
}

/**
 * One managed-configuration value.
 *
 * A tiny closed type rather than `Any`, so this module stays free of Android's
 * `Bundle` and the adapter cannot be handed something it has no way to write.
 */
sealed interface RestrictionValue {
    data class Flag(val value: Boolean) : RestrictionValue
    data class Number(val value: Int) : RestrictionValue
    data class Strings(val value: List<String>) : RestrictionValue
}

/**
 * The Chrome policy names, and how a [BrowserPolicy] turns into them.
 *
 * String literals rather than constants from a Chrome library, because there is
 * no such library — these are documented enterprise policy names, and the tests
 * assert the exact spellings so a typo cannot silently disable a filter.
 */
object BrowserRestrictions {

    const val CHROME = "com.android.chrome"

    const val FORCE_SAFE_SEARCH = "ForceGoogleSafeSearch"
    const val FORCE_YOUTUBE_RESTRICT = "ForceYouTubeRestrict"
    const val INCOGNITO_AVAILABILITY = "IncognitoModeAvailability"
    const val URL_BLOCKLIST = "URLBlocklist"
    const val URL_ALLOWLIST = "URLAllowlist"

    /** Chrome's value for "incognito is disabled". */
    const val INCOGNITO_DISABLED = 1

    /** Chrome's value for YouTube's moderate restricted mode. */
    const val YOUTUBE_MODERATE = 1

    /** The managed configuration for [policy], or empty when nothing is set. */
    fun forChrome(policy: BrowserPolicy): Map<String, RestrictionValue> = buildMap {
        if (policy.forceSafeSearch) {
            put(FORCE_SAFE_SEARCH, RestrictionValue.Flag(true))
        }
        if (policy.restrictYouTube) {
            put(FORCE_YOUTUBE_RESTRICT, RestrictionValue.Number(YOUTUBE_MODERATE))
        }
        if (policy.blockIncognito) {
            put(INCOGNITO_AVAILABILITY, RestrictionValue.Number(INCOGNITO_DISABLED))
        }
        if (policy.blockedUrls.isNotEmpty()) {
            put(URL_BLOCKLIST, RestrictionValue.Strings(policy.blockedUrls))
            // Only meaningful alongside a blocklist: on its own an allowlist
            // permits everything it names and everything it does not.
            if (policy.allowedUrls.isNotEmpty()) {
                put(URL_ALLOWLIST, RestrictionValue.Strings(policy.allowedUrls))
            }
        }
    }
}
