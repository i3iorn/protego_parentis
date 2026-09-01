package dev.schrammel.protego_parentis.setup

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle

/**
 * Tells the setup wizard which provisioning mode we want.
 *
 * Invisible: it answers and finishes. We always request a fully managed device —
 * the work-profile fallback described in the spec is a separate entry point taken
 * before provisioning starts, not a choice made here.
 */
class GetProvisioningModeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val result = Intent().putExtra(
            DevicePolicyManager.EXTRA_PROVISIONING_MODE,
            DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE,
        )
        setResult(RESULT_OK, result)
        finish()
    }
}
