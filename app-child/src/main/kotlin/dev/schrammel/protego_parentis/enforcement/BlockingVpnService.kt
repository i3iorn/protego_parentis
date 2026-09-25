package dev.schrammel.protego_parentis.enforcement

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import dev.schrammel.protego_parentis.AppRegistry

/**
 * A tunnel that goes nowhere, for the apps that should not reach the network (WF-4).
 *
 * The trick is that a VPN can be scoped to a list of apps. Only the blocked ones
 * are routed into this tunnel; everything else is untouched and reaches the
 * network exactly as before, including through the device-wide filtering resolver.
 * Their packets arrive at a file descriptor that is never read and never written,
 * so they simply stop. No traffic is inspected, decrypted or forwarded — this is a
 * dead end, not a middlebox, which is what makes it acceptable to run on a child's
 * phone at all.
 *
 * The system starts and restarts this, because the device owner has named it the
 * always-on VPN. That is also what grants the VPN consent: a parent never has to
 * tap a dialog on the child's phone.
 *
 * Deliberately without lockdown. With lockdown the *whole phone* loses the network
 * whenever the tunnel is down, which for a feature meant to block a couple of apps
 * would be a spectacular own goal.
 */
class BlockingVpnService : VpnService() {

    private var tunnel: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        establish()
        // Sticky so the system brings the block back after reclaiming the process.
        // The always-on setting does this too; both is not too many for a
        // restriction whose failure mode is silently letting traffic through.
        return START_STICKY
    }

    override fun onRevoke() {
        // Another VPN took over, or the user managed to revoke consent. Close
        // cleanly; the reconciler notices at the next tick and asks for it back.
        Log.w(TAG, "Tunnel revoked")
        close()
        stopSelf()
    }

    override fun onDestroy() {
        close()
        super.onDestroy()
    }

    /**
     * Opens a tunnel scoped to the blocked apps.
     *
     * Read from the policy store rather than passed in: the system starts this
     * service on its own after a reboot, with no intent of ours to carry a list.
     */
    private fun establish() {
        close()

        val blocked = AppRegistry.childStores.policy.networkBlockedPackages
        if (blocked.isEmpty()) {
            // Nothing to block. Running an empty tunnel would put a permanent VPN
            // key in the status bar for no reason at all.
            stopSelf()
            return
        }

        val builder = Builder()
            .setSession(getString(dev.schrammel.protego_parentis.R.string.app_name))
            // A documentation-range address and a default route, so the blocked
            // apps have somewhere to send packets and nowhere for them to go.
            .addAddress(ADDRESS, PREFIX_LENGTH)
            .addRoute(ANY_ADDRESS, 0)

        var scoped = 0
        var missing = 0
        blocked.forEach { pkg ->
            // Throws for an app that is no longer installed, which is ordinary
            // rather than exceptional — the list outlives uninstalls.
            runCatching { builder.addAllowedApplication(pkg) }
                .onSuccess { scoped++ }
                .onFailure { missing++ }
        }
        // A count, not the package names: logcat is not the app's own content-free
        // diagnostic log, and a blocklist derived from parental rules is not
        // something this app puts anywhere a package name can be read back out of.
        if (missing > 0) Log.d(TAG, "$missing blocked app(s) no longer installed")

        if (scoped == 0) {
            // Every named app has gone. Establishing now would capture *nothing*,
            // which Android reads as "capture everything" — the difference between
            // blocking three apps and blocking the phone.
            stopSelf()
            return
        }

        tunnel = runCatching { builder.establish() }
            .onFailure { Log.w(TAG, "Could not establish the tunnel", it) }
            .getOrNull()

        if (tunnel == null) stopSelf()
    }

    private fun close() {
        runCatching { tunnel?.close() }
        tunnel = null
    }

    private companion object {
        const val TAG = "DementorVpn"

        /** RFC 5737 documentation range: routable nowhere, by design. */
        const val ADDRESS = "192.0.2.1"
        const val PREFIX_LENGTH = 32
        const val ANY_ADDRESS = "0.0.0.0"
    }
}
