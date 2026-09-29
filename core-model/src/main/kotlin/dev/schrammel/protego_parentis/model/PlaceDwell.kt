package dev.schrammel.protego_parentis.model

/**
 * Which saved places the phone counts as being inside, once a reading has had
 * time to be confirmed.
 *
 * A single position fix inside a place doesn't make it an arrival, and a single
 * fix outside doesn't make it a departure. Driving past a place used to read as
 * arriving and leaving within a minute, firing a place rule and two alerts for
 * a place nobody stopped at. Now a place only changes once two fixes, at least
 * [DWELL_MILLIS] apart and with nothing in between that disagrees, both say so.
 *
 * Fixes, not checks: the app judges places from the newest stored fix, and two
 * checks a minute apart often read the same one. So progress is measured by
 * [PlaceReading.fixTimeMillis]. Reading the same fix twice changes nothing, which
 * also makes [advance] safe to call more than once per check.
 *
 * Pure and persisted by the caller, so the rule and the alerts see one answer.
 */
data class PlaceDwell(
    val confirmed: Set<PlaceId> = emptySet(),
    /** Places whose newest fixes disagree with [confirmed], by when that started. */
    val pendingSince: Map<PlaceId, Long> = emptyMap(),
    /**
     * False until the first reading. That one is taken as it is: a phone
     * already at home when the app starts, or updates to this version, hasn't
     * just arrived, and making it wait would announce it leaving and then
     * arriving again.
     */
    val seeded: Boolean = false,
) {

    /** Whether any place is waiting on a second fix, which is worth asking the phone for. */
    val awaitingConfirmation: Boolean get() = pendingSince.isNotEmpty()

    /**
     * The state after [reading], for the places in [known]. Places no longer
     * saved are forgotten.
     */
    fun advance(reading: PlaceReading, known: Set<PlaceId>): PlaceDwell {
        val inside = reading.inside intersect known
        if (!seeded) return PlaceDwell(confirmed = inside, seeded = true)

        val confirmedNow = confirmed.filterTo(mutableSetOf()) { it in known }
        val pendingNow = mutableMapOf<PlaceId, Long>()
        for (place in known) {
            val wasInside = place in confirmedNow
            if ((place in inside) == wasInside) continue

            val since = pendingSince[place]
            when {
                since == null -> pendingNow[place] = reading.fixTimeMillis
                reading.fixTimeMillis - since >= DWELL_MILLIS ->
                    if (wasInside) confirmedNow -= place else confirmedNow += place
                // The same fix again, a newer one too soon, or an older one
                // turning up late: none of them is a second reading.
                else -> pendingNow[place] = since
            }
        }
        return PlaceDwell(confirmed = confirmedNow, pendingSince = pendingNow, seeded = true)
    }

    companion object {
        /** How far apart the two agreeing fixes have to be. */
        const val DWELL_MILLIS = 60_000L
    }
}

/** The places one position fix is inside, and when that fix was taken. */
data class PlaceReading(val inside: Set<PlaceId>, val fixTimeMillis: Long)
