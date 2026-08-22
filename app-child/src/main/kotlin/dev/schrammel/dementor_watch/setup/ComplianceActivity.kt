package dev.schrammel.dementor_watch.setup

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import dev.schrammel.dementor_watch.R
import dev.schrammel.dementor_watch.dpc.PairingStore
import dev.schrammel.dementor_watch.enforcement.EnforcementService
import dev.schrammel.dementor_watch.ui.dp

/**
 * The post-provisioning compliance screen.
 *
 * Android hands control here once the device is provisioned, and will not finish
 * setup until this activity returns `RESULT_OK`. It is the only moment we have
 * the teen's attention on a fresh device, so the monitoring disclosure lives here
 * rather than being buried in a settings screen nobody opens.
 *
 * TODO(M2): redeem the pairing token against the server before continuing.
 * TODO(M2): request usage access, notification, and battery-exemption grants.
 * TODO(M3): let the teen choose their display name and avatar — it is their phone.
 */
class ComplianceActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
    }

    private fun buildLayout(): ScrollView {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(24)
            setPadding(pad, pad, pad, pad)
        }

        content.addView(
            TextView(this).apply {
                setText(R.string.compliance_title)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                setPadding(0, 0, 0, dp(16))
            },
        )

        content.addView(
            TextView(this).apply {
                setText(R.string.compliance_disclosure)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setLineSpacing(dp(4).toFloat(), 1f)
            },
        )

        content.addView(
            Button(this).apply {
                setText(R.string.compliance_continue)
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    topMargin = dp(28)
                }
                setOnClickListener { onAcknowledged() }
            },
        )

        return ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            addView(content)
        }
    }

    private fun onAcknowledged() {
        DisclosureRecord(this).markAcknowledged()

        val pairing = PairingStore(this).load()
        if (!pairing.isComplete) {
            // Not fatal: setup still completes and the app falls back to manual
            // code entry. Blocking here would strand the device half-provisioned.
            Log.w("DementorSetup", "Continuing without complete pairing details")
        }

        EnforcementService.start(this)
        setResult(RESULT_OK)
        finish()
    }

}

/**
 * Records that the teen was shown the monitoring disclosure and acknowledged it.
 *
 * Play's stalkerware policy requires the monitored person to be informed. Keep the
 * timestamp: it is the evidence, and it is also uploaded to the shared audit log
 * so the acknowledgement is visible to both sides.
 */
class DisclosureRecord(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("disclosure", Context.MODE_PRIVATE)

    /** Written synchronously: this is compliance evidence, not a UI preference. */
    @SuppressLint("ApplySharedPref")
    fun markAcknowledged(at: Long = System.currentTimeMillis()) {
        prefs.edit(commit = true) { putLong(KEY_ACKNOWLEDGED_AT, at) }
    }

    fun acknowledgedAt(): Long? =
        prefs.getLong(KEY_ACKNOWLEDGED_AT, 0L).takeIf { it > 0L }

    private companion object {
        const val KEY_ACKNOWLEDGED_AT = "acknowledged_at"
    }
}
