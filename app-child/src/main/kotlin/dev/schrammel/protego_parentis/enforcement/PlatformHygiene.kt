package dev.schrammel.protego_parentis.enforcement

import android.content.Context
import dev.schrammel.protego_parentis.AppRegistry
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.policy.reconcile.DevicePolicyPort
import dev.schrammel.protego_parentis.policy.reconcile.SupportMessages

/**
 * The two device-state assertions that ride along with enforcement but are not
 * the reconciler's business (see [PolicyReconciler]): whether site counting
 * matches the web-filter switch, and whether Android's enterprise wording has
 * been replaced with the family's own.
 *
 * Both are reads-with-side-effects on [DevicePolicyPort], which is exactly why
 * they live here rather than inside [EnforcementCoordinator.evaluate]: asking
 * for a snapshot (a query) should not mutate the platform. The coordinator
 * calls [apply] once per pass, only when this phone is the device owner —
 * neither assertion means anything on a phone without the authority.
 *
 * Network logging is desired state like anything else, but it has no
 * Enforcement field: it is a recording setting rather than a restriction, and
 * giving it one would invite the engine to start making decisions about it.
 * Support messages are re-asserted on every pass rather than written once at
 * provisioning time, and only when they differ — that is what makes a language
 * change take effect: the platform stores the literal string it was handed, so
 * the only way it follows the family's chosen language is for somebody to
 * notice and write it again. Reading first keeps that from being a write every
 * pass.
 */
class PlatformHygiene(
    context: Context,
    private val port: DevicePolicyPort,
) {

    private val appContext = context.applicationContext
    private val webFilterStore = AppRegistry.childStores.webFilter

    fun apply(deviceOwner: Boolean) {
        if (!deviceOwner) return
        val wanted = webFilterStore.countVisits
        if (port.isNetworkLoggingEnabled() != wanted) port.setNetworkLoggingEnabled(wanted)
        applySupportMessages()
    }

    private fun applySupportMessages() {
        val wanted = SupportMessages(
            short = appContext.getString(R.string.support_message_short),
            long = appContext.getString(
                R.string.support_message_long,
                appContext.getString(R.string.app_name),
            ),
        )
        if (port.currentSupportMessages() != wanted) port.setSupportMessages(wanted)
    }
}
