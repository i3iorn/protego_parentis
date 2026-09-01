package dev.schrammel.protego_parentis.rules

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject
import java.time.LocalDate

/** One site and how often it was reached for. */
data class DomainVisits(val domain: String, val count: Int)

/**
 * Today's web activity, counted and nothing more (WF-3).
 *
 * This is the most privacy-sensitive thing the app records, and the shape of it is
 * the whole argument for why it is acceptable:
 *
 * - **Domains, never URLs.** The platform gives us DNS lookups, so a path was
 *   never available; but even the hostname is trimmed to its registrable part, so
 *   the record says `reddit.com` rather than which corner of it.
 * - **Counts, never times.** How often, not when. The app already refuses to log
 *   when individual apps were used, and a browsing timeline would be a far more
 *   intimate version of exactly that.
 * - **Today, never a history.** The day rolls and yesterday is gone. There is no
 *   archive here for anyone to go through later, including us.
 * - **The same view on both phones.** The child sees precisely what their parents
 *   see, which is the promise the rest of the product already makes.
 *
 * What this cannot do is see inside an app. A video watched in a feed is not a DNS
 * lookup for anything meaningful, and the screen says so rather than letting a
 * parent believe the list is a complete account of an afternoon.
 */
class WebActivityStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** The day currently being counted. Rolling it discards yesterday entirely. */
    private var day: String
        get() = prefs.getString(KEY_DAY, null).orEmpty()
        set(value) = prefs.edit { putString(KEY_DAY, value) }

    fun visits(today: LocalDate): List<DomainVisits> {
        if (day != today.toString()) return emptyList()
        return decode(prefs.getString(KEY_COUNTS, null))
            .map { (domain, count) -> DomainVisits(domain, count) }
            .sortedByDescending { it.count }
    }

    /** How many lookups were refused by the filtering resolver today. */
    fun blockedAttempts(today: LocalDate): Int =
        if (day == today.toString()) prefs.getInt(KEY_BLOCKED, 0) else 0

    /**
     * Adds a batch of hostnames to today's count.
     *
     * [blocked] are the lookups that resolved to nothing, which a filtering
     * resolver is what usually causes — counted separately so a family can see the
     * filter working rather than having to take it on trust.
     */
    fun record(today: LocalDate, hostnames: List<String>, blocked: Int) = synchronized(LOCK) {
        val rolled = day != today.toString()
        val counts = if (rolled) mutableMapOf() else decode(prefs.getString(KEY_COUNTS, null))

        hostnames.asSequence()
            .mapNotNull(::registrable)
            .forEach { domain -> counts[domain] = (counts[domain] ?: 0) + 1 }

        // Bounded, and by count rather than alphabetically: an afternoon of
        // browsing can touch hundreds of domains through embedded content, and a
        // list nobody can read is not a summary.
        val kept = counts.entries
            .sortedByDescending { it.value }
            .take(MAX_DOMAINS)
            .associate { it.key to it.value }

        prefs.edit {
            putString(KEY_DAY, today.toString())
            putString(KEY_COUNTS, encode(kept))
            putInt(KEY_BLOCKED, (if (rolled) 0 else prefs.getInt(KEY_BLOCKED, 0)) + blocked)
        }
    }

    fun clear() {
        prefs.edit { clear() }
    }

    private fun encode(counts: Map<String, Int>): String =
        JSONObject(counts.mapValues { it.value }).toString()

    private fun decode(raw: String?): MutableMap<String, Int> {
        if (raw.isNullOrBlank()) return mutableMapOf()
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return mutableMapOf()
        return json.keys().asSequence()
            .associateWith { json.optInt(it, 0) }
            .filterValues { it > 0 }
            .toMutableMap()
    }

    private companion object {
        val LOCK = Any()

        const val FILE_NAME = "web_activity"
        const val KEY_DAY = "day"
        const val KEY_COUNTS = "counts"
        const val KEY_BLOCKED = "blocked"

        /** Enough to see where an afternoon went; few enough to actually read. */
        const val MAX_DOMAINS = 40
    }
}

/**
 * Trims a hostname to the part worth showing.
 *
 * `www.bbc.co.uk` and `static.files.bbc.co.uk` both become `bbc.co.uk`: a parent
 * asked which sites, not which content-delivery host, and collapsing them is both
 * more readable and less revealing than the full name.
 *
 * Deliberately a short heuristic rather than a public-suffix list. Being wrong
 * groups two domains that should have been separate, or splits one that should
 * have been joined — neither of which misleads anybody about what was visited,
 * and a megabyte of suffix data on a phone would be a poor trade for tidier rows.
 */
internal fun registrable(hostname: String): String? {
    val host = hostname.trim().trimEnd('.').lowercase()
    if (host.isEmpty() || host.length > MAX_HOSTNAME || host.any { it.isWhitespace() }) return null
    // An address is not a site name, and showing one tells a reader nothing.
    if (host.all { it.isDigit() || it == '.' } || host.contains(':')) return null

    val parts = host.split('.').filter(String::isNotEmpty)
    if (parts.size < 2) return null

    val last = parts.last()
    val secondLast = parts[parts.size - 2]
    val keep = if (parts.size >= 3 && secondLast in COMMON_SECOND_LEVEL && last.length <= 3) 3 else 2

    return parts.takeLast(minOf(keep, parts.size)).joinToString(".")
}

private const val MAX_HOSTNAME = 253

/** The handful that would otherwise collapse every UK or Australian site into one. */
private val COMMON_SECOND_LEVEL = setOf("co", "com", "org", "net", "gov", "ac", "edu")
