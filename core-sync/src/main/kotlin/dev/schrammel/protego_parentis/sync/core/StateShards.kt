package dev.schrammel.protego_parentis.sync.core

/**
 * The replica, split into one encoded string per top-level section (#291).
 *
 * Stored as a single string, every change — one chat line, one audit record —
 * re-encoded and rewrote the whole replica, audit and chat history included:
 * tens of milliseconds and a megabyte-sized write per change at the sizes a
 * family reaches within the 28-day window, on a phone several times slower
 * than that measurement. Split by section, a change rewrites only the
 * sections it touched.
 *
 * Pure: the store decides where the strings live.
 */
object StateShards {

    /** The section a path belongs to — its first segment. */
    fun sectionOf(path: String): String = path.substringBefore(ReplicationRules.SEPARATOR)

    /** The sections [paths] fall in. */
    fun sectionsOf(paths: Iterable<String>): Set<String> = paths.mapTo(mutableSetOf(), ::sectionOf)

    /**
     * The encoded form of each of [sections] as they stand in [state]; null
     * for a section with nothing left in it, which the store removes.
     */
    fun encode(state: Map<String, StateEntry>, sections: Set<String>): Map<String, String?> {
        val bySection = state.entries
            .filter { sectionOf(it.key) in sections }
            .groupBy({ sectionOf(it.key) }, { StateOp(it.key, it.value.value, it.value.lamport, it.value.origin) })
        return sections.associateWith { section -> bySection[section]?.let(StateCodec::encodeOps) }
    }

    /** One section's entries, from what [encode] produced for it. */
    fun decodeSection(raw: String?): Map<String, StateEntry> =
        StateCodec.decodeOps(raw).associate { it.path to it.toEntry() }
}
