package dev.schrammel.protego_parentis.context

/**
 * When [ContextProvider.refreshPlacesFixIfStale] may spend a location fix (#290).
 *
 * The budget is deliberately small — at most one network fix a quarter hour,
 * and none while any app has recently fixed the phone for us — because the
 * enforcement tick runs every minute while place rules exist, and a request on
 * every tick would be the battery cost the passive design set out to avoid.
 */
internal object PlaceFixPolicy {

    /** A fix younger than this is good enough; ask for nothing. */
    const val STALE_AFTER_MILLIS = 15 * 60 * 1000L

    /** Never ask more often than this, whether or not the last ask produced a fix. */
    const val MIN_GAP_MILLIS = 15 * 60 * 1000L

    /** [fixAgeMillis] and [sinceLastRequestMillis] are null when there is no fix, or no request yet. */
    fun shouldRequest(fixAgeMillis: Long?, sinceLastRequestMillis: Long?): Boolean {
        val stale = fixAgeMillis == null || fixAgeMillis > STALE_AFTER_MILLIS
        val due = sinceLastRequestMillis == null || sinceLastRequestMillis >= MIN_GAP_MILLIS
        return stale && due
    }
}
