package dev.schrammel.dementor_watch.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.schrammel.dementor_watch.R
import dev.schrammel.dementor_watch.diagnostics.Diagnostics
import dev.schrammel.dementor_watch.sync.SyncCoordinator

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
 */
class PrivacyActivity : DementorActivity() {

    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dimen(R.dimen.screen_padding)
            setPadding(pad, pad, pad, pad)
        }
        setContentView(
            ScrollView(this).apply {
                layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
                addView(container)
            },
        )
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        container.removeAllViews()
        container.addView(heading(getString(R.string.privacy_title), R.style.TextAppearance_Dementor_Title))

        section(R.string.privacy_measured_header, R.string.privacy_measured_body)
        section(R.string.privacy_never_header, R.string.privacy_never_body)

        // Whether anything leaves the phone at all depends on configuration, so
        // this section reports the live answer rather than a general claim.
        val settings = SyncCoordinator(this).settings()
        if (settings.isUsable) {
            section(R.string.privacy_sent_header, R.string.privacy_sent_body)
            container.addView(body(getString(R.string.privacy_sent_server, settings.serverUrl)))
        } else {
            section(R.string.privacy_sent_header, R.string.privacy_sent_off)
        }

        section(R.string.privacy_log_header, R.string.privacy_log_body)
        container.addView(
            Button(this).apply {
                setText(R.string.privacy_view_log)
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                setOnClickListener { startActivity(DiagnosticsActivity.intent(this@PrivacyActivity)) }
            },
        )
    }

    private fun section(headerRes: Int, bodyRes: Int) {
        container.addView(heading(getString(headerRes), R.style.TextAppearance_Dementor_Section))
        container.addView(body(getString(bodyRes)))
    }

    private fun heading(text: String, appearance: Int) = TextView(this).apply {
        this.text = text
        styleAs(appearance)
        setPadding(0, dimen(R.dimen.section_spacing), 0, dimen(R.dimen.space_xxs))
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        styleAs(R.style.TextAppearance_Dementor_Body)
    }

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

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

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dimen(R.dimen.screen_padding)
            setPadding(pad, pad, pad, pad)

            addView(
                TextView(context).apply {
                    setText(R.string.diagnostics_title)
                    styleAs(R.style.TextAppearance_Dementor_Title)
                },
            )
            addView(
                Button(context).apply {
                    setText(R.string.diagnostics_share)
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                    setOnClickListener { share() }
                },
            )
            addView(
                Button(context).apply {
                    setText(R.string.diagnostics_clear)
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                    setOnClickListener {
                        Diagnostics.clear(this@DiagnosticsActivity)
                        text.text = logText()
                    }
                },
            )
            addView(text.apply { setPadding(0, dimen(R.dimen.space_lg), 0, 0) })
        }

        text.text = logText()
        setContentView(
            ScrollView(this).apply {
                layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
                addView(content)
            },
        )
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
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        fun intent(context: Context) = Intent(context, DiagnosticsActivity::class.java)
    }
}
