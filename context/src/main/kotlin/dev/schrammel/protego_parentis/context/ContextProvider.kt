package dev.schrammel.protego_parentis.context

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.util.Log
import androidx.core.content.ContextCompat
import dev.schrammel.protego_parentis.model.DeviceContext
import dev.schrammel.protego_parentis.model.DeviceTag
import dev.schrammel.protego_parentis.model.Place

/** Which of the context sources this phone is currently able to read. */
data class ContextCapabilities(
    val canReadWifi: Boolean,
    val canReadLocation: Boolean,
    val canReadNearbyDevices: Boolean,
)

/**
 * Turns the phone's surroundings into the resolved [DeviceContext] the rules see.
 *
 * This is the only place in the app that touches a coordinate, an SSID or a
 * Bluetooth address, and none of them leave it. A location becomes "inside these
 * places" and is discarded; the rules engine downstream is structurally unable to
 * log or transmit a position because it never receives one.
 *
 * Every source fails closed. If a permission is missing the value is simply
 * absent, which makes conditions evaluate to false — a revoked permission can
 * never turn a block into an allowance by accident.
 */
class ContextProvider(context: Context) {

    private val appContext = context.applicationContext

    fun capabilities(): ContextCapabilities = ContextCapabilities(
        canReadWifi = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION),
        canReadLocation = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION),
        canReadNearbyDevices = hasPermission(Manifest.permission.BLUETOOTH_CONNECT),
    )

    fun current(places: List<Place>): DeviceContext = DeviceContext(
        wifiSsid = wifiSsid(),
        nearbyDevices = nearbyDevices(),
        insidePlaces = insidePlaces(places),
        batteryPercent = BatteryReader.percent(appContext),
    )

    // ---------- Wi-Fi ----------

    /**
     * The connected network's name, or null.
     *
     * Android only reveals the SSID to apps holding a location permission, because
     * a network name is itself a location signal. Without it the value is null and
     * every Wi-Fi rule simply does not apply.
     */
    private fun wifiSsid(): String? {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return null

        return runCatching {
            val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
                ?: return null
            val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
                ?: return null
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null

            val info = capabilities.transportInfo as? WifiInfo ?: return null
            // The platform wraps the name in quotes, and reports this placeholder
            // when it will not tell us.
            info.ssid
                ?.trim('"')
                ?.takeIf { it.isNotBlank() && it != UNKNOWN_SSID }
        }.getOrElse {
            Log.d(TAG, "Could not read the network name", it)
            null
        }
    }

    // ---------- places ----------

    /**
     * Which saved places the phone is currently inside.
     *
     * Uses the last known fix rather than requesting a new one: rules are checked
     * every few minutes, and waking the GPS on that cadence would be a serious
     * battery cost for a question that rarely changes. A stale fix means a rule
     * lags rather than misfires.
     */
    @SuppressLint("MissingPermission")
    private fun insidePlaces(places: List<Place>): Set<dev.schrammel.protego_parentis.model.PlaceId> {
        if (places.isEmpty()) return emptySet()
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return emptySet()

        val location = lastKnownLocation() ?: return emptySet()

        return places
            .filter { place -> within(location, place) }
            .mapTo(mutableSetOf()) { it.id }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(): Location? = runCatching {
        val manager = appContext.getSystemService(LocationManager::class.java) ?: return null
        // Network first: far cheaper, and accurate enough for a place with a
        // seventy-five metre radius.
        listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }
            .maxByOrNull { it.time }
    }.getOrElse {
        Log.d(TAG, "Could not read a location", it)
        null
    }

    private fun within(location: Location, place: Place): Boolean {
        val results = FloatArray(1)
        Location.distanceBetween(
            location.latitude,
            location.longitude,
            place.latitude,
            place.longitude,
            results,
        )
        // The fix's own accuracy is added to the radius, so a poor fix does not
        // repeatedly drop the phone out of a place it has not left.
        return results[0] <= place.radiusMetres + location.accuracy
    }

    // ---------- nearby devices ----------

    /**
     * Paired Bluetooth devices that are currently connected.
     *
     * Connection rather than a proximity scan, deliberately. Continuous BLE
     * scanning is a heavy battery cost and a far broader permission, and for the
     * case this exists for — "is a parent's phone with them" — a paired,
     * connected device is a good enough and much cheaper proxy.
     */
    @SuppressLint("MissingPermission")
    private fun nearbyDevices(): Set<DeviceTag> {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return emptySet()

        return runCatching {
            val manager = appContext.getSystemService(BluetoothManager::class.java)
                ?: return emptySet()
            val adapter = manager.adapter ?: return emptySet()
            if (!adapter.isEnabled) return emptySet()

            manager.getConnectedDevices(BluetoothProfile.GATT)
                .plus(manager.getConnectedDevices(BluetoothProfile.GATT_SERVER))
                .mapNotNullTo(mutableSetOf()) { device ->
                    device.address?.let(::DeviceTag)
                }
        }.getOrElse {
            Log.d(TAG, "Could not read connected devices", it)
            emptySet()
        }
    }

    // ---------- plumbing ----------

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "DementorContext"

        /** What Android reports when it will not disclose the network name. */
        const val UNKNOWN_SSID = "<unknown ssid>"
    }
}
