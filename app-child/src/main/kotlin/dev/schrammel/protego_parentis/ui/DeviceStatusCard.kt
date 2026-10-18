package dev.schrammel.protego_parentis.ui

import android.app.Activity
import android.view.View
import android.widget.TextView
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.enforcement.EnforcementCoordinator

/**
 * The phone's enforcement state — monitor-only, managed, or a DNS failure — in
 * one card, shared by the rules list and the controls hub so the two can never
 * say different things (rules-UX phase B).
 *
 * Starts on the reassuring reading rather than blank. The lookup is off the main
 * thread, so an empty card would flash on every open, and a status line that
 * blinks looks like a fault whatever it eventually says.
 */
internal fun Activity.deviceStatusCard(): View {
    val status = TextView(this).apply {
        setText(R.string.rules_device_status_monitor)
        styleAs(R.style.TextAppearance_Dementor_Body)
    }

    Background.load(this, {
        val coordinator = EnforcementCoordinator(this)
        // Read, not recomputed. Asking for a fresh evaluation here would mean
        // opening a screen drove DevicePolicyManager, which is a side effect no
        // one asked a status line to have.
        coordinator.isDeviceOwner() to coordinator.dnsFailedLastPass()
    }) { (owner, dnsFailed) ->
        status.setText(
            when {
                dnsFailed -> R.string.rules_dns_failed
                owner -> R.string.rules_device_status_owner
                else -> R.string.rules_device_status_monitor
            },
        )
    }

    return card { addView(status) }
}
