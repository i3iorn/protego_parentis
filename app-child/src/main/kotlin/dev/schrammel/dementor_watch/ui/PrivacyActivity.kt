package dev.schrammel.dementor_watch.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import dev.schrammel.dementor_watch.R
import dev.schrammel.dementor_watch.diagnostics.Diagnostics
import dev.schrammel.dementor_watch.rules.WebFilterStore
import dev.schrammel.dementor_watch.sync.SyncCoordinator

private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

/**
 * "What leaves this phone" — the child's view of their own privacy.
 *
 * Not behind the PIN, and that is the point. The product's promise is that the
 * monitored person can see the monitoring in full; a privacy page a child cannot
 * open is marketing rather than transparency.
 *
 * It states three things plainly: what is measured on the device, what is sent off
 * it, and what is never collected at all. The last list is the one that matters
 * most to a teenager, and it is the one most parental control apps cannot write.
 *
 * Each claim gets its own card. This screen is the longest stretch of prose in the
 * app and it used to arrive as one unbroken column, which is the format people
 * skip. Separate surfaces make it a set of statements that can be read one at a
 * time — and each one is short enough that reading it costs nothing.
 */
class PrivacyActivity : DementorActivity() {

    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        column = contentColumn()
        showColumn(column)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        column.removeAllViews()
        column.addView(screenTitle(getString(R.string.privacy_title)))

        claim(R.string.privacy_measured_header, R.string.privacy_measured_body)
        claim(R.string.privacy_never_header, R.string.privacy_never_body)

        // Whether anything leaves the phone at all depends on configuration, so
        // this section reports the live answer rather than a general claim.
        val settings = SyncCoordinator(this).settings()
        if (settings.isUsable) {
            claim(
                R.string.privacy_sent_header,
                R.string.privacy_sent_body,
                getString(R.string.privacy_sent_server, settings.serverUrl),
            )
            // Named explicitly, because this is the one message carrying what was
            // used rather than what the app did. A transparency screen that omits
            // the interesting part is worse than none.
            claim(R.string.privacy_summary_header, R.string.privacy_summary_body)
            // Location is the one place this app deliberately breaks its own rule
            // about coordinates never leaving the device, so it says so at length
            // rather than in passing.
            claim(R.string.privacy_location_header, R.string.privacy_location_body)
            // The most intimate thing that leaves this phone, and it was missing
            // from the one screen whose whole job is to say what leaves it.
            if (WebFilterStore(this).countVisits) {
                claim(R.string.privacy_web_header, R.string.privacy_web_body)
            }
        } else {
            claim(R.string.privacy_sent_header, R.string.privacy_sent_off)
        }

        claim(R.string.privacy_log_header, R.string.privacy_log_body)
        column.addView(
            actionButton().apply {
                setText(R.string.privacy_view_log)
                setOnClickListener { startActivity(DiagnosticsActivity.intent(context)) }
            },
        )
    }

    /** One statement about what this app does, on its own surface. */
    private fun claim(headerRes: Int, bodyRes: Int, extra: String? = null) {
        column.addView(
            card {
                addView(
                    TextView(this@PrivacyActivity).apply {
                        setText(headerRes)
                        styleAs(R.style.TextAppearance_Dementor_Subtitle)
                    },
                )
                addView(
                    TextView(this@PrivacyActivity).apply {
                        setText(bodyRes)
                        styleAs(R.style.TextAppearance_Dementor_Body)
                        setPadding(0, dimen(R.dimen.space_xs), 0, 0)
                    },
                )
                if (extra != null) addView(captionText(extra))
            },
        )
    }

    companion object {
        fun intent(context: Context) = Intent(context, PrivacyActivity::class.java)
    }
}

/**
 * Shows the diagnostic log and offers to share it.
 *
 * Readable by the child, deliberately. Sharing a log you are not allowed to read
 * is not something anyone should be asked to do, and the log is built to contain
 * nothing that would make reading it uncomfortable — see [Diagnostics].
 */
class DiagnosticsActivity : DementorActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = TextView(this).apply {
            styleAs(R.style.TextAppearance_Dementor_Caption)
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }

        val column = contentColumn().apply {
            addView(screenTitle(getString(R.string.diagnostics_title)))

            // Side by side rather than two full-width bars down the screen. They
            // are a pair of small utilities, and stacking them gave them the
            // weight of the thing the screen is actually for.
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.START
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)

                    addView(
                        actionButton().apply {
                            setText(R.string.diagnostics_share)
                            setOnClickListener { share() }
                        },
                    )
                    addView(
                        actionButton().apply {
                            setText(R.string.diagnostics_clear)
                            (layoutParams as LinearLayout.LayoutParams).marginStart =
                                dimen(R.dimen.space_sm)
                            setOnClickListener {
                                Diagnostics.clear(this@DiagnosticsActivity)
                                text.text = logText()
                            }
                        },
                    )
                },
            )

            addView(card { addView(text) })
        }

        text.text = logText()
        showColumn(column)
    }

    private fun logText() = Diagnostics.asText(
        context = this,
        header = getString(R.string.diagnostics_header),
        empty = getString(R.string.diagnostics_empty),
    )

    private fun share() {
        // Plain text in the intent rather than a file: nothing is written to
        // shared storage, and the user picks the destination themselves.
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, logText())
        }
        startActivity(Intent.createChooser(send, getString(R.string.diagnostics_share)))
    }

    companion object {
        fun intent(context: Context) = Intent(context, DiagnosticsActivity::class.java)
    }
}
