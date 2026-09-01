package dev.schrammel.protego_parentis.context

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.util.Log
import androidx.core.content.ContextCompat
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Where the phone is, as one answer to one question. */
data class Position(
    val latitude: Double,
    val longitude: Double,
    val accuracyMetres: Int,
    val fixedAt: Instant,
)

/**
 * Answers "where is this phone" once, when asked (LOC-2).
 *
 * The rest of this app goes to some trouble never to let a coordinate reach the
 * rules engine or the network — [dev.schrammel.protego_parentis.model.DeviceContext]
 * carries resolved place ids and nothing else, precisely so the part most likely
 * to grow new features is structurally unable to leak a position.
 *
 * This is the deliberate exception, and it is kept as narrow as it can be:
 * - a single fix in answer to a single request, never a stream and never a trail;
 * - nothing is stored, so there is no history here for anyone to obtain later;
 * - the child is told every time, without exception, and their own screen shows
 *   the same list of requests their parents can see.
 *
 * A parental control app that quietly tracks a teenager is the thing this product
 * exists not to be. A parent who can ask, where asking is visible and recorded, is
 * a different arrangement — and the visibility is not decoration, it is the whole
 * of what makes it different.
 */
class LocationSharing(context: Context) {

    private val appContext = context.applicationContext

    fun canLocate(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * A current fix, or null.
     *
     * Blocks for up to [TIMEOUT_SECONDS]; callers must be off the main thread.
     * Asks for a fresh fix rather than taking the last known one, because "where
     * are they now" answered with a position from this morning is worse than no
     * answer at all — it is a wrong answer that looks right.
     */
    @SuppressLint("MissingPermission")
    fun locate(): Position? {
        if (!canLocate()) return null
        val manager = appContext.getSystemService(LocationManager::class.java) ?: return null

        val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
            ?: return lastKnown(manager)

        val latch = CountDownLatch(1)
        var found: Location? = null
        val cancellation = CancellationSignal()

        runCatching {
            manager.getCurrentLocation(
                provider,
                cancellation,
                executor,
            ) { location ->
                found = location
                latch.countDown()
            }
        }.onFailure {
            Log.w(TAG, "Could not ask for a fix", it)
            return lastKnown(manager)
        }

        if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            // Indoors, or a cold GPS. Cancel rather than leave the radio running,
            // and fall back to whatever the phone already knew.
            cancellation.cancel()
            return lastKnown(manager)
        }
        return found?.toPosition() ?: lastKnown(manager)
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(manager: LocationManager): Position? = runCatching {
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }
            .maxByOrNull { it.time }
            ?.toPosition()
    }.getOrNull()

    private fun Location.toPosition() = Position(
        latitude = latitude,
        longitude = longitude,
        accuracyMetres = accuracy.toInt(),
        fixedAt = Instant.ofEpochMilli(time),
    )

    private companion object {
        const val TAG = "DementorLocation"
        const val TIMEOUT_SECONDS = 20L

        /**
         * Shared and daemon, like the sync executor: a locate can arrive from the
         * enforcement tick or from a screen, and a thread per request would leak
         * one every time.
         */
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "dementor-location").apply { isDaemon = true }
        }
    }
}
