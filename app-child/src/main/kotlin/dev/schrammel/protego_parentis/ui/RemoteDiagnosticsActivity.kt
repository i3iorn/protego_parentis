package dev.schrammel.protego_parentis.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.diagnostics.Diagnostics
import dev.schrammel.protego_parentis.sync.RemoteDiagnosticsStore
import dev.schrammel.protego_parentis.sync.SyncGraph
import dev.schrammel.protego_parentis.sync.core.DeviceId

/**
 * A parent's view of a managed phone's diagnostic log, without holding that
 * phone — the same content-free record [DiagnosticsActivity] already shows
 * locally, requested and cached the same way [AppPickerActivity] requests
 * and caches a remote app list.
 *
 * Deliberately does not show [dev.schrammel.protego_parentis.diagnostics.CrashCapture]'s
 * raw stack trace: that one can name a rule, a category or a place, and
 * carrying it off the phone at all is a separate, more sensitive decision
 * this screen does not make on its own — see
 * [dev.schrammel.protego_parentis.sync.core.SyncMessageType.DIAGNOSTICS_REPORT]'s
 * own doc comment.
 */
class RemoteDiagnosticsActivity : ParentGatedActivity() {

    // DeviceId is a value class — lateinit is not allowed on one, since it
    // has no null-representable backing field the way a plain reference
    // type does. Lazy sidesteps that by wrapping it in an ordinary Lazy<DeviceId>
    // instead of a raw field, and reads no earlier than onCreate did anyway.
    private val childId: DeviceId by lazy { DeviceId(intent.getStringExtra(EXTRA_DEVICE_ID).orEmpty()) }
    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        column = contentColumn()
        showColumn(column)
    }

    override fun onGatedContentVisible() = render()

    private fun render() {
        column.removeAllViews()
        column.addView(screenTitle(getString(R.string.remote_diagnostics_title)))
        column.addView(captionText(getString(R.string.remote_diagnostics_hint)))

        column.addView(
            actionButton().apply {
                setText(R.string.remote_diagnostics_request)
                setOnClickListener {
                    SyncGraph.get(this@RemoteDiagnosticsActivity).publishDiagnosticsRequest()
                    toast(getString(R.string.remote_diagnostics_requested))
                    pollForFreshLog()
                }
            },
        )

        // The log is a stored string that can run to tens of KB, and rendering
        // it here meant reading and re-formatting it on the main thread — on
        // every poll tick while a fresh log is being waited for, too.
        Background.load(
            this,
            {
                RemoteDiagnosticsStore(this).log(childId)?.let { raw ->
                    Diagnostics.asText(
                        raw = raw,
                        header = getString(R.string.diagnostics_header),
                        empty = getString(R.string.diagnostics_empty),
                    )
                }
            },
        ) { text ->
            if (text == null) {
                column.addView(bodyText(getString(R.string.remote_diagnostics_empty)))
            } else {
                column.addView(cardFor(text))
                column.addView(shareButton(text))
            }
        }
    }

    private fun cardFor(text: String): View =
        card {
            addView(
                TextView(this@RemoteDiagnosticsActivity).apply {
                    this.text = text
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTextIsSelectable(true)
                    styleAs(R.style.TextAppearance_Dementor_Caption)
                },
            )
        }

    private fun shareButton(text: String): View =
        actionButton().apply {
            setText(R.string.diagnostics_share)
            setOnClickListener {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                startActivity(Intent.createChooser(send, getString(R.string.diagnostics_share)))
            }
        }

    /**
     * Checks back for the answer to a log request, a few times over the next
     * minute — mirrors [AppPickerActivity.pollForFreshInventory], which the
     * same request/cache shape relies on for the same reason: the reply
     * arrives asynchronously over sync with nothing to call back into this
     * screen when it lands.
     */
    private fun pollForFreshLog(attempt: Int = 0) {
        if (attempt >= MAX_LOG_POLL_ATTEMPTS) return
        handler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            render()
            pollForFreshLog(attempt + 1)
        }, LOG_POLL_INTERVAL_MILLIS)
    }

    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val EXTRA_DEVICE_ID = "deviceId"

        /** How often [pollForFreshLog] checks back after a request. */
        private const val LOG_POLL_INTERVAL_MILLIS = 5_000L

        /** ~a minute of checking — long enough for realtime sync's usual case, not forever. */
        private const val MAX_LOG_POLL_ATTEMPTS = 12

        fun intent(context: Context, deviceId: DeviceId): Intent =
            Intent(context, RemoteDiagnosticsActivity::class.java)
                .putExtra(EXTRA_DEVICE_ID, deviceId.value)
    }
}
