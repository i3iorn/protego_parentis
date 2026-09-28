package dev.schrammel.protego_parentis.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import dev.schrammel.protego_parentis.AppRegistry
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.diagnostics.CrashCapture
import dev.schrammel.protego_parentis.diagnostics.Diagnostics
import dev.schrammel.protego_parentis.rules.Feature
import dev.schrammel.protego_parentis.sync.SyncGraph
import dev.schrammel.protego_parentis.sync.appVersionName
import dev.schrammel.protego_parentis.update.ApkInstaller
import dev.schrammel.protego_parentis.update.PendingUpdateStore
import dev.schrammel.protego_parentis.update.UpdateChecker
import dev.schrammel.protego_parentis.update.UpdateOutcome
import java.io.File

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
        val settings = SyncGraph.get(this).settings()
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
            // Said plainly rather than left implied. Messages are the one thing
            // on this list the child chose to send — but "I typed it" is not the
            // same as "I know where it is kept", and both ends keep a copy.
            if (AppRegistry.childStores.featureFlags.isEnabled(Feature.CHAT)) {
                claim(R.string.privacy_chat_header, R.string.privacy_chat_body)
            }
            // The most intimate thing that leaves this phone, and it was missing
            // from the one screen whose whole job is to say what leaves it.
            if (AppRegistry.childStores.webFilter.countVisits) {
                claim(R.string.privacy_web_header, R.string.privacy_web_body)
            }
        } else {
            claim(R.string.privacy_sent_header, R.string.privacy_sent_off)
        }

        claim(R.string.privacy_log_header, R.string.privacy_log_body)
        // Only true once sync is configured at all — see the same guard above
        // this claim's own header comment.
        if (settings.isUsable) {
            claim(R.string.privacy_log_remote_header, R.string.privacy_log_remote_body)
        }
        column.addView(
            actionButton().apply {
                setText(R.string.privacy_view_log)
                setOnClickListener { startActivity(DiagnosticsActivity.intent(context)) }
            },
        )
        // The technical log above says what the app did; this is the
        // human-readable record of what actually changed — every rule edit,
        // approval and setting — which is this app's real transparency
        // mechanism (TR-3). A screen about what leaves the phone with no path
        // to it was a gap, not a choice.
        column.addView(
            actionButton().apply {
                setText(R.string.privacy_view_history)
                setOnClickListener { startActivity(AuditActivity.intent(context)) }
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

    private lateinit var updateCard: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = TextView(this).apply {
            styleAs(R.style.TextAppearance_Dementor_Caption)
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }

        updateCard = card { }

        val column = contentColumn().apply {
            addView(screenTitle(getString(R.string.diagnostics_title)))

            addView(updateCard)

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
                                // Erases the phone's own evidence trail — used to
                                // diagnose tampering or a crash — with no undo.
                                // Given the same weight as "Share" one tap ago,
                                // it deserves the same confirm-first treatment
                                // every other consequential action in this app
                                // already gets.
                                confirmDialog(
                                    R.string.diagnostics_clear,
                                    R.string.diagnostics_clear_confirm,
                                    R.string.diagnostics_clear,
                                ) {
                                    Diagnostics.clear(this@DiagnosticsActivity)
                                    CrashCapture.clear(this@DiagnosticsActivity)
                                    text.text = logText()
                                }
                            }
                        },
                    )
                },
            )

            addView(card { addView(text) })
            renderCrashCard(this)
        }

        renderUpdateCard()
        text.text = logText()
        showColumn(column)
    }

    /**
     * The one thing [Diagnostics]' own closed-set log cannot say: what an
     * evaluation failure actually threw. See [CrashCapture]'s own doc
     * comment for why this exists as a separate, clearly-labelled card
     * rather than folded into the shareable log above — the log's whole
     * design promise is "nothing free-form", and a raw stack trace is
     * exactly that.
     */
    private fun renderCrashCard(column: LinearLayout) {
        val trace = CrashCapture.lastEvaluationFailure(this) ?: return
        column.addView(
            card {
                addView(
                    TextView(this@DiagnosticsActivity).apply {
                        setText(R.string.diagnostics_crash_header)
                        styleAs(R.style.TextAppearance_Dementor_Subtitle)
                    },
                )
                addView(
                    TextView(this@DiagnosticsActivity).apply {
                        setText(R.string.diagnostics_crash_body)
                        styleAs(R.style.TextAppearance_Dementor_Caption)
                        setPadding(0, dimen(R.dimen.space_xs), 0, dimen(R.dimen.space_xs))
                    },
                )
                addView(
                    TextView(this@DiagnosticsActivity).apply {
                        text = trace
                        typeface = android.graphics.Typeface.MONOSPACE
                        setTextIsSelectable(true)
                        styleAs(R.style.TextAppearance_Dementor_Caption)
                    },
                )
            },
        )
    }

    /**
     * Either a "check for updates" button, or — once [UpdateChecker] has
     * downloaded something this phone could not apply unattended (see
     * [ApkInstaller]'s own doc comment) — an "install now" button in its
     * place. Rebuilt rather than diffed, since there is at most a handful of
     * views here and this screen is opened rarely enough that the cost never
     * matters.
     */
    private fun renderUpdateCard() {
        updateCard.removeAllViews()
        updateCard.addView(
            TextView(this).apply {
                text = getString(R.string.update_version, appVersionName(this@DiagnosticsActivity))
                styleAs(R.style.TextAppearance_Dementor_Subtitle)
            },
        )

        val pending = PendingUpdateStore(this).pending
        if (pending != null) {
            updateCard.addView(captionText(getString(R.string.update_ready, pending.versionName)))
            updateCard.addView(
                actionButton().apply {
                    setText(R.string.update_install_now)
                    setOnClickListener {
                        ApkInstaller.installWithConfirmation(this@DiagnosticsActivity, File(pending.apkPath))
                    }
                },
            )
            return
        }

        val status = captionText("")
        updateCard.addView(status)
        updateCard.addView(
            actionButton().apply {
                setText(R.string.update_check_now)
                setOnClickListener {
                    status.setText(R.string.update_checking)
                    Background.load(this@DiagnosticsActivity, { UpdateChecker.checkNow(applicationContext) }) { outcome ->
                        if (outcome == UpdateOutcome.DOWNLOADED_PENDING_CONFIRMATION) {
                            renderUpdateCard()
                        } else {
                            status.text = describeOutcome(outcome)
                        }
                    }
                }
            },
        )
    }

    private fun describeOutcome(outcome: UpdateOutcome): String = getString(
        when (outcome) {
            UpdateOutcome.UP_TO_DATE -> R.string.update_up_to_date
            UpdateOutcome.CHECK_FAILED -> R.string.update_check_failed
            UpdateOutcome.DOWNLOAD_FAILED -> R.string.update_download_failed
            UpdateOutcome.REJECTED -> R.string.update_rejected
            UpdateOutcome.INSTALLED_SILENTLY -> R.string.update_installed_silently
            UpdateOutcome.DOWNLOADED_PENDING_CONFIRMATION -> R.string.update_ready_generic
        },
    )

    private fun logText() = Diagnostics.asText(
        context = this,
        header = getString(R.string.diagnostics_header),
        empty = getString(R.string.diagnostics_empty),
    )

    private fun share() {
        // Plain text in the intent rather than a file: nothing is written to
        // shared storage, and the user picks the destination themselves.
        val crash = CrashCapture.lastEvaluationFailure(this)
        val text = if (crash == null) {
            logText()
        } else {
            logText() + "\n\n" + getString(R.string.diagnostics_crash_header) + "\n" + crash
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, getString(R.string.diagnostics_share)))
    }

    companion object {
        fun intent(context: Context) = Intent(context, DiagnosticsActivity::class.java)
    }
}
