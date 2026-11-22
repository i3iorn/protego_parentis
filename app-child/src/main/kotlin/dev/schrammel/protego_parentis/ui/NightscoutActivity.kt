package dev.schrammel.protego_parentis.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.glucose.GlucoseSource
import dev.schrammel.protego_parentis.glucose.GlucoseUnit
import dev.schrammel.protego_parentis.glucose.NightscoutClient
import dev.schrammel.protego_parentis.glucose.NightscoutProblem
import dev.schrammel.protego_parentis.glucose.NightscoutStore
import dev.schrammel.protego_parentis.glucose.NightscoutUrl
import java.time.Duration
import java.time.Instant

/**
 * Connects this phone to the family's Nightscout server, so rules can use
 * glucose levels.
 *
 * Only ever on the managed phone itself: it is the phone that fetches and
 * enforces, and the token never leaves it. Asks for a read-only token rather
 * than the server's API secret, which could also write treatments.
 */
class NightscoutActivity : ParentGatedActivity() {
    /**
     * Rule editing is a run of small changes: one PIN, then the whole visit.
     */
    override val keepsSessionPinned: Boolean = true


    private lateinit var store: NightscoutStore
    private lateinit var column: LinearLayout

    /** The outcome of the last "save and test", shown until the screen is left. */
    private var tested: NightscoutClient.Result? = null

    override fun supportsReadOnly() = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = NightscoutStore(this)
        column = contentColumn()
        showColumn(column)
    }

    override fun onGatedContentVisible() = render()

    private fun render() {
        column.removeAllViews()
        column.addView(screenTitle(getString(R.string.nightscout_title)))
        column.addView(bodyText(getString(R.string.nightscout_explainer)))
        if (!canEdit) column.addView(readOnlyBanner { requestUnlock { onGatedContentVisible() } })

        column.addView(sectionHeader(getString(R.string.nightscout_status_header)))
        column.addView(captionText(status()))

        val urlInput = EditText(this).apply {
            hint = getString(R.string.nightscout_url_hint)
            setText(store.baseUrl.orEmpty())
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isEnabled = canEdit
        }
        val tokenInput = EditText(this).apply {
            hint = getString(R.string.nightscout_token_hint)
            setSingleLine()
            // Never prefilled: the token is sealed and stays that way on screen.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isEnabled = canEdit
        }
        column.addView(sectionHeader(getString(R.string.nightscout_connection_header)))
        column.addView(urlInput)
        column.addView(tokenInput)
        column.addView(captionText(getString(R.string.nightscout_token_explainer)))

        if (canEdit) {
            column.addView(
                actionButton().apply {
                    setText(R.string.nightscout_save_test)
                    setOnClickListener { saveAndTest(urlInput.text.toString(), tokenInput.text.toString()) }
                },
            )
            if (store.isConfigured) {
                column.addView(
                    actionButton().apply {
                        setText(R.string.nightscout_disconnect)
                        setOnClickListener {
                            confirmDialog(
                                R.string.nightscout_disconnect,
                                R.string.nightscout_disconnect_confirm,
                                R.string.nightscout_disconnect,
                            ) {
                                store.clear()
                                tested = null
                                render()
                            }
                        }
                    },
                )
            }
        }
    }

    private fun saveAndTest(urlText: String, tokenText: String) {
        val url = NightscoutUrl.normalise(urlText)
        if (url == null) {
            toast(R.string.nightscout_url_invalid)
            return
        }
        val token = tokenText.trim()
        if (!NightscoutUrl.isPlausibleToken(token)) {
            toast(R.string.nightscout_token_invalid)
            return
        }
        if (!store.save(url, token)) {
            toast(R.string.nightscout_cannot_store)
            return
        }
        toast(R.string.nightscout_testing)
        Background.load(this, { GlucoseSource.fetch(this) }) { result ->
            tested = result
            render()
        }
    }

    /** What the phone knows right now, in one or two lines. */
    private fun status(): String {
        if (!store.isConfigured) return getString(R.string.nightscout_not_connected)
        val lastTest = tested
        if (lastTest is NightscoutClient.Result.Failed) return problemText(lastTest.problem)

        val reading = store.latest ?: return store.lastProblem?.let { problemText(it) }
            ?: getString(R.string.nightscout_no_reading_yet)
        val unit = store.units ?: GlucoseUnit.MG_DL
        val minutes = Duration.between(reading.at, Instant.now()).toMinutes().coerceAtLeast(0)
        return getString(R.string.nightscout_last_reading, glucoseText(reading.mgdl, unit), minutes)
    }

    private fun problemText(problem: NightscoutProblem): String = getString(
        when (problem) {
            NightscoutProblem.NOT_ALLOWED -> R.string.nightscout_problem_not_allowed
            NightscoutProblem.UNREACHABLE -> R.string.nightscout_problem_unreachable
            NightscoutProblem.NO_READINGS -> R.string.nightscout_problem_no_readings
        },
    )

    companion object {
        fun intent(context: Context) = Intent(context, NightscoutActivity::class.java)
    }
}

/** [mgdl] in [unit], with the unit named, or in both units when this phone doesn't know which the family uses. */
internal fun Context.glucoseText(mgdl: Int, unit: GlucoseUnit?): String = when (unit) {
    GlucoseUnit.MMOL_L -> getString(R.string.glucose_mmol, GlucoseUnit.MMOL_L.format(mgdl))
    GlucoseUnit.MG_DL -> getString(R.string.glucose_mgdl, GlucoseUnit.MG_DL.format(mgdl))
    null -> getString(
        R.string.glucose_both,
        getString(R.string.glucose_mmol, GlucoseUnit.MMOL_L.format(mgdl)),
        getString(R.string.glucose_mgdl, GlucoseUnit.MG_DL.format(mgdl)),
    )
}
