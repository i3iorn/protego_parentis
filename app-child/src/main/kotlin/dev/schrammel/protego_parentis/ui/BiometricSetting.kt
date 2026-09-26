package dev.schrammel.protego_parentis.ui

import android.app.Activity
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Toast
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.audit.Audit
import dev.schrammel.protego_parentis.model.AuditAction
import dev.schrammel.protego_parentis.model.AuditActor
import dev.schrammel.protego_parentis.security.BiometricAvailability
import dev.schrammel.protego_parentis.security.BiometricGate
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.RoleStore

/**
 * The fingerprint-or-face switch, built once for both screens that offer it.
 *
 * The two sides need the identical control and, more to the point, the identical
 * warning — and a warning that exists in two places is a warning that ends up
 * saying two different things.
 *
 * The switch lives behind the PIN wherever it appears. Turning it on is a
 * decision about who can change the rules, and the person most interested in
 * making that decision is the one the rules apply to.
 */
private fun Activity.recordAndRefresh(wanted: Boolean, onChanged: () -> Unit) {
    Audit.record(
        context = this,
        actor = AuditActor.PARENT,
        action = AuditAction.BIOMETRIC_UNLOCK_CHANGED,
        after = Audit.flag(wanted),
    )
    onChanged()
}

internal fun Activity.biometricSetting(onChanged: () -> Unit): LinearLayout {
    val gate = BiometricGate(this)
    val onChildDevice = RoleStore(this).roleOrChild == DeviceRole.CHILD

    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL

        addView(captionText(getString(R.string.biometric_body)))

        // Not offered on the managed phone at all. Android cannot tell one
        // enrolled finger from another, and the fingers enrolled on a child's
        // own phone are the child's, so here it was a bypass of the PIN
        // behind a warning. Turned off if an earlier build left it on.
        if (onChildDevice) {
            if (gate.isEnabled) gate.disable()
            addView(bodyText(getString(R.string.biometric_child_unavailable)))
            return@apply
        }

        when (gate.availability()) {
            BiometricAvailability.NOT_ENROLLED -> {
                addView(bodyText(getString(R.string.biometric_none)))
                return@apply
            }

            BiometricAvailability.UNSUPPORTED -> {
                addView(bodyText(getString(R.string.biometric_unsupported)))
                return@apply
            }

            BiometricAvailability.INVALIDATED ->
                addView(bodyText(getString(R.string.biometric_invalidated)))

            BiometricAvailability.READY -> Unit
        }

        addView(
            CheckBox(context).apply {
                setText(R.string.biometric_enable)
                isChecked = gate.isEnabled
                setOnCheckedChangeListener { box, wanted ->
                    if (!wanted) {
                        gate.disable()
                        recordAndRefresh(wanted = false, onChanged = onChanged)
                        return@setOnCheckedChangeListener
                    }

                    if (gate.enable()) {
                        recordAndRefresh(wanted = true, onChanged = onChanged)
                        return@setOnCheckedChangeListener
                    }

                    // The keystore refused. Say so rather than leaving a switch
                    // that looks on and does nothing when somebody needs it.
                    //
                    // The listener comes off before the box is put back, because
                    // setting it here fires this same listener again — which wrote
                    // a spurious "turned off" line into the shared history and
                    // re-entered the render it was already inside.
                    box.setOnCheckedChangeListener(null)
                    box.isChecked = false
                    Toast.makeText(context, R.string.biometric_failed, Toast.LENGTH_LONG).show()
                    onChanged()
                }
            },
        )
    }
}
