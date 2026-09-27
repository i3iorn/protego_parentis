package dev.schrammel.protego_parentis.glucose

import android.content.Context
import dev.schrammel.protego_parentis.model.GlucoseReading
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps [NightscoutStore.latest] current while a rule reads glucose.
 *
 * Fetches on a thread of its own rather than the enforcement worker, so a
 * slow or unreachable server delays nothing: the tick uses whatever reading
 * it already has, and the next tick sees the new one. At most one fetch runs
 * at a time, and at most one starts per [MIN_GAP] whether or not the last
 * one worked, matching the five-minute rhythm a CGM reports on.
 */
internal object GlucoseSource {

    private val MIN_GAP: Duration = Duration.ofMinutes(4)
    private val UNITS_EVERY: Duration = Duration.ofDays(1)

    private val executor = Executors.newSingleThreadExecutor { Thread(it, "dementor-glucose") }
    private val inFlight = AtomicBoolean(false)

    fun latest(context: Context): GlucoseReading? = NightscoutStore(context).latest

    fun refreshIfDue(context: Context, now: Instant = Instant.now()) {
        val store = NightscoutStore(context)
        if (!store.isConfigured) return
        val last = store.lastAttemptAt
        if (last != null && Duration.between(last, now) < MIN_GAP) return
        if (!inFlight.compareAndSet(false, true)) return
        store.lastAttemptAt = now
        val appContext = context.applicationContext
        executor.execute {
            try {
                fetch(appContext, now)
            } finally {
                inFlight.set(false)
            }
        }
    }

    /**
     * One fetch, now, on the calling thread: the setup screen's "test"
     * button, run from a background load. Also what [refreshIfDue] runs.
     */
    fun fetch(context: Context, now: Instant = Instant.now()): NightscoutClient.Result? {
        val store = NightscoutStore(context)
        val url = store.baseUrl ?: return null
        val token = store.token() ?: return null
        val client = NightscoutClient(url, token)

        val result = client.latestReading()
        when (result) {
            is NightscoutClient.Result.Read -> {
                store.latest = result.reading
                store.lastProblem = null
            }
            // The previous reading stays: it ages into "stale" on its own,
            // which is what a rule about missing data is written against.
            is NightscoutClient.Result.Failed -> store.lastProblem = result.problem
        }

        val checked = store.unitsCheckedAt
        if (store.units == null || checked == null || Duration.between(checked, now) >= UNITS_EVERY) {
            client.units()?.let {
                store.units = it
                store.unitsCheckedAt = now
            }
        }
        return result
    }
}
