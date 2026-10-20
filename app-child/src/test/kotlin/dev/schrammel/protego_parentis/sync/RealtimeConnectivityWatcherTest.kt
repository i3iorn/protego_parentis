package dev.schrammel.protego_parentis.sync

import android.content.Context
import android.net.ConnectivityManager
import androidx.core.content.edit
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

    /**
     * Seals the passphrase the way a real device would. Robolectric has no
     * AndroidKeyStore, so [SecretVault.seal] always fails and [SyncConfigStore]
     * refuses to store anything sealed — which would leave every settings read
     * seeing an unconfigured family. Writing the value under the *legacy* key
     * instead exercises the migration path, which returns the plaintext it was
     * asked to migrate even when sealing it fails. [SyncConfigStore.join] must
     * have run first: its own store step removes the legacy key.
     */
    private fun joinWithPassphrase(token: FamilyToken, passphrase: String) {
        SyncConfigStore(context).join(token, passphrase)
        context.getSharedPreferences(SyncConfigStore.FILE_NAME, Context.MODE_PRIVATE)
            .edit { putString(SyncConfigStore.KEY_PASSPHRASE_LEGACY, passphrase) }
    }

    @Test
    fun `network becoming available restarts the realtime service`() {
        joinWithPassphrase(
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
