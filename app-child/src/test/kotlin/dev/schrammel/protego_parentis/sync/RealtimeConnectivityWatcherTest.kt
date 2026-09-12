package dev.schrammel.protego_parentis.sync

import android.net.ConnectivityManager
import dev.schrammel.protego_parentis.sync.core.FamilyToken
import dev.schrammel.protego_parentis.sync.core.TransportKind
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork

/**
 * The one thing that matters about [RealtimeConnectivityWatcher]: a network
 * coming back actually reopens the realtime stream, so the service is never
 * left dead for longer than the next network flap just because nobody opened
 * a screen or rebooted the phone.
 */
@RunWith(RobolectricTestRunner::class)
class RealtimeConnectivityWatcherTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `network becoming available restarts the realtime service`() {
        SyncConfigStore(context).join(
            FamilyToken(TransportKind.NTFY, "https://ntfy.sh", "watcher-test"),
            "secret",
        )

        RealtimeConnectivityWatcher.install(context)

        val manager = context.getSystemService(ConnectivityManager::class.java)
        val callback = shadowOf(manager).networkCallbacks.single()
        callback.onAvailable(ShadowNetwork.newInstance(1))

        val started = shadowOf(context).nextStartedService
        assertEquals(RealtimeSyncService::class.java.name, started?.component?.className)
    }

    @Test
    fun `does nothing when the family is not configured`() {
        RealtimeConnectivityWatcher.install(context)

        val manager = context.getSystemService(ConnectivityManager::class.java)
        val callback = shadowOf(manager).networkCallbacks.single()
        callback.onAvailable(ShadowNetwork.newInstance(1))

        assertEquals(null, shadowOf(context).nextStartedService)
    }
}
