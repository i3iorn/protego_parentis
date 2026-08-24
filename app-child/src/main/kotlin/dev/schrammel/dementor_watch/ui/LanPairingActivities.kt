package dev.schrammel.dementor_watch.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import dev.schrammel.dementor_watch.R
import dev.schrammel.dementor_watch.sync.LanPairing
import dev.schrammel.dementor_watch.sync.NearbyPhone
import dev.schrammel.dementor_watch.sync.PairingWire
import dev.schrammel.dementor_watch.sync.SyncConfigStore
import java.util.concurrent.TimeUnit

/**
 * Offers this phone's family to another phone on the same network.
 *
 * Reached from the connected state of the family screen, which on the managed
 * phone is already behind the PIN — so whoever is here has proved they may hand
 * the family out.
 *
 * The offer is temporary and visibly so. It stops when the countdown ends, when
 * the screen closes, and when the phone is locked. An advertisement left standing
 * is the thing this design is arranged to avoid, so there is no path that leaves
 * one running.
 *
 * FLAG_SECURE because the code being compared is on screen alongside the fact that
 * a family is being handed over, and this screen sits one step from the one
 * holding the passphrase.
 */
class LanOfferActivity : DementorActivity() {

    private lateinit var column: LinearLayout
    private lateinit var status: TextView

    private var offer: LanPairing.Offer? = null
    private var countdown: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )

        column = contentColumn()
        status = TextView(this).apply {
            styleAs(R.style.TextAppearance_Dementor_Subtitle)
        }

        column.addView(screenTitle(getString(R.string.pair_offer_title)))
        column.addView(bodyText(getString(R.string.pair_offer_intro)))
        // The one caveat worth stating rather than burying: the family code — not
        // the passphrase — crosses the network here.
        column.addView(captionText(getString(R.string.pair_network_warning)))
        column.addView(card { addView(status) })
        showColumn(column)
    }

    override fun onStart() {
        super.onStart()
        if (!SyncConfigStore(this).load().isUsable) {
            // Nothing to offer. Reachable if the family was left in another window.
            finish()
            return
        }
        begin()
    }

    /** Stopped in onStop rather than onDestroy: a backgrounded phone stops offering. */
    override fun onStop() {
        super.onStop()
        end()
    }

    private fun begin() {
        status.setText(R.string.pair_offer_waiting)

        offer = LanPairing.Offer(
            context = this,
            onRequest = { code, decide -> runOnUiThread { ask(code, decide) } },
            onStopped = { reason -> runOnUiThread { stopped(reason) } },
        ).also { it.start() }

        countdown = object : CountDownTimer(
            TimeUnit.SECONDS.toMillis(PairingWire.OFFER_SECONDS),
            TimeUnit.SECONDS.toMillis(1),
        ) {
            override fun onTick(remaining: Long) {
                val seconds = TimeUnit.MILLISECONDS.toSeconds(remaining).toInt()
                status.text = resources.getQuantityString(
                    R.plurals.pair_offer_countdown,
                    seconds,
                    seconds,
                )
            }

            override fun onFinish() = end()
        }.also { it.start() }
    }

    /**
     * A phone has connected. Somebody has to look at it before anything is sent.
     *
     * The code is the whole check: it is on the other phone's screen, and matching
     * it is how a person knows the phone that will receive the family is the one
     * in their hand rather than another one on the same network.
     */
    private fun ask(code: String, decide: (Boolean) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(R.string.pair_offer_asking)
            .setMessage(getString(R.string.pair_offer_compare, code))
            .setPositiveButton(R.string.pair_offer_allow) { _, _ ->
                decide(true)
                status.setText(R.string.pair_offer_sent)
                end()
            }
            .setNegativeButton(R.string.pair_offer_refuse) { _, _ -> decide(false) }
            // Dismissing without choosing is a refusal, not a pause. The socket
            // is being held open on the other side and something has to answer it.
            .setOnCancelListener { decide(false) }
            .show()
    }

    private fun stopped(reason: String?) {
        if (reason != null) status.setText(R.string.pair_unavailable)
    }

    private fun end() {
        countdown?.cancel()
        countdown = null
        offer?.stop()
        offer = null
    }

    companion object {
        fun intent(context: Context) = Intent(context, LanOfferActivity::class.java)
    }
}

/**
 * Finds a phone offering a family, and asks it for the code.
 *
 * What arrives is the family token and nothing else — the same half that a QR
 * code carries. The passphrase is still typed on the previous screen, because it
 * never travels anywhere, over any network, in any form.
 */
class LanFindActivity : DementorActivity() {

    private lateinit var column: LinearLayout
    private lateinit var status: TextView
    private lateinit var results: LinearLayout

    private var finder: LanPairing.Finder? = null
    private val found = mutableListOf<NearbyPhone>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        column = contentColumn()
        status = TextView(this).apply { styleAs(R.style.TextAppearance_Dementor_Subtitle) }
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        column.addView(screenTitle(getString(R.string.pair_find_title)))
        column.addView(bodyText(getString(R.string.pair_find_intro)))
        column.addView(captionText(getString(R.string.pair_network_warning)))
        column.addView(card { addView(status) })
        column.addView(results)
        showColumn(column)
    }

    override fun onStart() {
        super.onStart()
        look()
    }

    override fun onStop() {
        super.onStop()
        finder?.stop()
        finder = null
    }

    /**
     * Starts looking, stopping whatever was looking before.
     *
     * Called on resume and again after a refusal. The stop is the point: without
     * it, trying a second time left the first discovery registered — two listeners
     * reporting the same phones, and only the newer one ever shut down.
     */
    private fun look() {
        finder?.stop()
        status.setText(R.string.pair_find_looking)
        found.clear()
        results.removeAllViews()

        finder = LanPairing.Finder(
            context = this,
            onFound = { phone -> runOnUiThread { add(phone) } },
            onStopped = { reason ->
                runOnUiThread { if (reason != null) status.setText(R.string.pair_unavailable) }
            },
        ).also { it.start() }
    }

    private fun add(phone: NearbyPhone) {
        // Resolution fires again for the same service on some networks, and a list
        // that grows a duplicate row every few seconds is a list nobody can use.
        if (found.any { it.host == phone.host && it.port == phone.port }) return
        found.add(phone)

        status.setText(R.string.pair_find_found)
        results.removeAllViews()
        results.addView(
            group(
                found.map { candidate ->
                    navigationRow(
                        candidate.name,
                        getString(R.string.pair_find_detail),
                    ) { request(candidate) }
                },
            ),
        )
    }

    private fun request(phone: NearbyPhone) {
        val shown = PairingWire.code()
        status.text = getString(R.string.pair_find_waiting, shown)
        results.removeAllViews()

        finder?.request(phone, shown) { answer ->
            runOnUiThread { answered(answer) }
        }
    }

    private fun answered(answer: PairingWire.Answer) {
        when (answer) {
            is PairingWire.Answer.Granted -> {
                setResult(
                    RESULT_OK,
                    Intent().putExtra(EXTRA_CODE, dev.schrammel.dementor_watch.sync.FamilyTokens.encode(answer.token)),
                )
                finish()
            }

            // A refusal and a stranger on the port are shown the same way. There is
            // nothing a person could usefully do differently, and a screen that
            // distinguished them would be telling whoever is on the network which
            // of their attempts got further.
            PairingWire.Answer.Denied,
            PairingWire.Answer.Unreadable,
            -> {
                look()
                status.setText(R.string.pair_find_refused)
            }
        }
    }

    companion object {
        const val EXTRA_CODE = "code"

        fun intent(context: Context) = Intent(context, LanFindActivity::class.java)
    }
}
