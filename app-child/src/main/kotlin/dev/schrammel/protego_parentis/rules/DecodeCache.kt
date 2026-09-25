package dev.schrammel.protego_parentis.rules

/**
 * A decoded store value, cached against the exact string it was decoded from.
 *
 * The enforcement tick re-reads the whole policy up to every fifteen seconds,
 * and the stores that feed [LocalPolicyStore.load] each decoded their entire
 * JSON document on every getter call — per tick, for data that only changes
 * when a parent edits a rule. This cache keys on the raw string itself rather
 * than on a version counter, so it is correct no matter which code path wrote
 * the file (several sibling stores are written without the policy version
 * being bumped): a mismatch between the stored string and the cached one is
 * a miss, everything else is a hit.
 *
 * Shared across store *instances* on purpose: screens and services construct
 * stores per call, so an instance-level cache would never stay warm. The
 * decoded lists are treated as immutable — every caller observes the
 * read-modify-copy pattern — so handing out the cached list is safe.
 */
internal class DecodeCache<T> {

    private val lock = Any()
    private var raw: String? = null
    private var hasRaw: Boolean = false
    private var value: T? = null

    fun get(current: String?, decode: (String?) -> T): T = synchronized(lock) {
        if (!hasRaw || raw != current) {
            raw = current
            hasRaw = true
            value = decode(current)
        }
        @Suppress("UNCHECKED_CAST")
        value as T
    }
}