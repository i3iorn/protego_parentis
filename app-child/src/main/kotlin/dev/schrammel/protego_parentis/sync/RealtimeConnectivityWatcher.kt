package dev.schrammel.protego_parentis.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * Reopens the realtime stream as soon as a network comes back.
 *
 * [RealtimeSyncService] already retries its own dropped *connections* with
 * backoff, but it cannot restart itself once the OS has killed the *service*
 * outright — which is exactly what aggressive OEM battery management does to
 * a background foreground service that nobody is looking at. Until now the
 * only things that re-armed it were a reboot
 * ([dev.schrammel.protego_parentis.enforcement.BootReceiver]) or a person
 * opening a screen; a parent's phone that stayed backgrounded in between
 * could go without a live connection for however long that stretch lasted,
 * with only [ParentPollWorker]'s fifteen-minute poll underneath it.
 *
 * A network transition — Wi-Fi dropping and returning, Doze letting a burst
 * of connectivity through — is the moment the OS is most likely to also let
 * a foreground service start, so it is the cheapest hook available for
 * narrowing that gap without inventing new architecture.
 * [RealtimeSyncService.start] is idempotent and itself checks whether the
 * family is configured, so calling it on every network change the OS
 * reports costs nothing when the stream is already open or nothing is set
 * up yet.
 */
object RealtimeConnectivityWatcher {

    @Volatile private var installed = false

    /**
     * Robolectric builds a fresh application for every test method, but this
     * object survives them — so without a reset the next test's `install`
     * is a no-op and its fresh app ends up with no registered callback at all.
     * Call it from [TestApplication]'s boot sequence, beside the other
     * `resetForTest` calls.
     */
    fun resetForTest() {
        installed = false
    }

    /** Call once, from [dev.schrammel.protego_parentis.DementorApplication]. */
    fun install(context: Context) {
        if (installed) return
        installed = true

        val appContext = context.applicationContext
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        manager.registerNetworkCallback(
            request,
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    RealtimeSyncService.start(appContext)
                }
            },
        )
    }
}
