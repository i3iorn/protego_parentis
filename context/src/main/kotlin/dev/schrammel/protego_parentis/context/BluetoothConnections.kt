package dev.schrammel.protego_parentis.context

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * The devices connected over classic Bluetooth right now (#289).
 *
 * `BluetoothManager.getConnectedDevices` only answers for GATT, which is
 * Bluetooth LE — a car stereo, a headset or another phone connected the
 * ordinary way never appears there, so a "near a device" rule naming one
 * never held. The platform has no query for classic connections, only the
 * ACL broadcasts as they happen, so this keeps its own set: seeded from the
 * A2DP and headset profiles when [watch] starts, then kept current by the
 * broadcasts while the enforcement service runs.
 */
object BluetoothConnections {

    private const val TAG = "DementorBluetooth"
    private val connected = mutableSetOf<String>()

    /** Upper-case addresses, the form the platform reports them in. */
    fun connected(): Set<String> = synchronized(connected) { connected.toSet() }

    internal fun onConnected(address: String) = synchronized(connected) { connected += address.uppercase() }

    internal fun onDisconnected(address: String) = synchronized(connected) { connected -= address.uppercase() }

    internal fun clear() = synchronized(connected) { connected.clear() }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val device = deviceOf(intent) ?: return
            when (intent?.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> onConnected(device.address)
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> onDisconnected(device.address)
            }
        }
    }

    /**
     * Starts tracking; returns how to stop. Without BLUETOOTH_CONNECT the
     * broadcasts carry no device and the set simply stays empty.
     */
    fun watch(context: Context): () -> Unit {
        val appContext = context.applicationContext
        clear()
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        // A system broadcast, so it still arrives with the receiver not exported.
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        seed(appContext)
        return { runCatching { appContext.unregisterReceiver(receiver) } }
    }

    /** What was already connected before the broadcasts started arriving. */
    @SuppressLint("MissingPermission")
    private fun seed(context: Context) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET).forEach { kind ->
            runCatching {
                adapter.getProfileProxy(
                    context,
                    object : BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                            runCatching { proxy.connectedDevices.forEach { onConnected(it.address) } }
                            adapter.closeProfileProxy(profile, proxy)
                        }

                        override fun onServiceDisconnected(profile: Int) = Unit
                    },
                    kind,
                )
            }.onFailure { Log.d(TAG, "Could not read connected devices for profile $kind", it) }
        }
    }

    private fun deviceOf(intent: Intent?): BluetoothDevice? =
        intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
}
