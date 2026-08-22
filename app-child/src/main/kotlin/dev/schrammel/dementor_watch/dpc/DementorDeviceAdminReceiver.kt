package dev.schrammel.dementor_watch.dpc

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.util.Log
import dev.schrammel.dementor_watch.provisioning.ProvisioningPayload

/**
 * The device owner component.
 *
 * Every `DevicePolicyManager` call in the app is made against this receiver, and
 * Android delivers provisioning results here. Its fully qualified name is baked
 * into the setup QR code, so renaming or moving it invalidates every QR already
 * in the wild — treat the class name as a published constant.
 */
class DementorDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "Device admin enabled")
    }

    /**
     * Fired once, after the setup wizard has installed us as device owner.
     *
     * The admin extras bundle is the only channel that survives provisioning, so
     * the pairing token travels in it. Persist immediately: this callback does not
     * fire again, and without the token the device cannot find its family.
     */
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        val extras = intent.getParcelableExtra(
            DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE,
            PersistableBundle::class.java,
        )

        val pairing = PairingDetails(
            token = extras?.getString(ProvisioningPayload.ADMIN_EXTRA_PAIRING_TOKEN),
            familyId = extras?.getString(ProvisioningPayload.ADMIN_EXTRA_FAMILY_ID),
            childId = extras?.getString(ProvisioningPayload.ADMIN_EXTRA_CHILD_ID),
        )
        PairingStore(context).save(pairing)

        if (!pairing.isComplete) {
            // Recoverable: the compliance screen falls back to manual code entry.
            Log.w(TAG, "Provisioning finished without complete pairing details")
        }
        Log.i(TAG, "Provisioning complete, paired=${pairing.isComplete}")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        // Should be unreachable on a provisioned device — device owner cannot be
        // disabled by the user. If it happens, the server learns from the missing
        // heartbeat (TR-2) rather than from this callback.
        Log.w(TAG, "Device admin disabled")
    }

    companion object {
        private const val TAG = "DementorDPC"

        fun componentName(context: Context): ComponentName =
            ComponentName(context.applicationContext, DementorDeviceAdminReceiver::class.java)
    }
}
