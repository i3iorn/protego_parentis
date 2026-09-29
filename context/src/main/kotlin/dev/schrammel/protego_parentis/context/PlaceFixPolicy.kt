package dev.schrammel.protego_parentis.context

import dev.schrammel.protego_parentis.model.PlaceDwell

/**
 * When [ContextProvider.refreshPlacesFixIfStale] may spend a location fix (#290).
 *
 * The budget is deliberately small — at most one network fix a quarter hour,
 * and none while any app has recently fixed the phone for us — because the
 * enforcement tick runs every minute while place rules exist, and a request on
 * every tick would be the battery cost the passive design set out to avoid.
 *
 * The one exception is a place waiting to confirm an arrival or departure,
 * which needs a second fix a minute after the first (see [PlaceDwell]).
 */
internal object PlaceFixPolicy {

    /** A fix younger than this is good enough; ask for nothing. */
    const val STALE_AFTER_MILLIS = 15 * 60 * 1000L

    /** Never ask more often than this, whether or not the last ask produced a fix. */
    const val MIN_GAP_MILLIS = 15 * 60 * 1000L

    /**
     * How soon a second fix is worth asking for while a place is [confirming]
     * an arrival or departure. The same as the gap [PlaceDwell] needs between
     * its two fixes, so the one asked for can be the one that confirms.
     */
    const val CONFIRM_AFTER_MILLIS = PlaceDwell.DWELL_MILLIS

    /**
     * [fixAgeMillis] and [sinceLastRequestMillis] are null when there is no fix, or no request yet.
     *
     * [confirming] is true while a place waits on a second fix. Without an ask
     * that fix could take a quarter hour to turn up, and so could the arrival.
     * It costs one network fix a minute, only while something is pending, and
     * pending ends one way or the other within a couple of fixes.
     */
    fun shouldRequest(fixAgeMillis: Long?, sinceLastRequestMillis: Long?, confirming: Boolean = false): Boolean {
        val staleAfter = if (confirming) CONFIRM_AFTER_MILLIS else STALE_AFTER_MILLIS
        val gap = if (confirming) CONFIRM_AFTER_MILLIS else MIN_GAP_MILLIS
        val stale = fixAgeMillis == null || fixAgeMillis > staleAfter
        val due = sinceLastRequestMillis == null || sinceLastRequestMillis >= gap
        return stale && due
    }
}
