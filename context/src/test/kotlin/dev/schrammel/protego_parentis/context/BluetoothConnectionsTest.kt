package dev.schrammel.protego_parentis.context

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Classic Bluetooth connections, tracked from the ACL broadcasts (#289): the
 * platform's own query only answers for Bluetooth LE, so a car or a headset
 * was never "near".
 */
@RunWith(RobolectricTestRunner::class)
class BluetoothConnectionsTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private var stop: (() -> Unit)? = null

    @After
    fun tearDown() {
        stop?.invoke()
        BluetoothConnections.clear()
    }

    private fun device(address: String): BluetoothDevice =
        checkNotNull(app.getSystemService(BluetoothManager::class.java).adapter).getRemoteDevice(address)

    private fun broadcast(action: String, address: String) {
        app.sendBroadcast(Intent(action).putExtra(BluetoothDevice.EXTRA_DEVICE, device(address)))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `a device that connects is counted, and stops counting when it disconnects`() {
        stop = BluetoothConnections.watch(app)

        broadcast(BluetoothDevice.ACTION_ACL_CONNECTED, "A4:C1:38:0F:22:9B")
        assertEquals(setOf("A4:C1:38:0F:22:9B"), BluetoothConnections.connected())

        broadcast(BluetoothDevice.ACTION_ACL_DISCONNECTED, "A4:C1:38:0F:22:9B")
        assertTrue(BluetoothConnections.connected().isEmpty())
    }
}
