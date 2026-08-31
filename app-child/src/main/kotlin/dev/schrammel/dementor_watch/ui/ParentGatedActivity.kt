package dev.schrammel.dementor_watch.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import dev.schrammel.dementor_watch.R
import dev.schrammel.dementor_watch.security.DeviceRole
import dev.schrammel.dementor_watch.security.ParentSession
import dev.schrammel.dementor_watch.security.RoleStore

/**
 * Base class for every screen only a parent may see.
 *
 * Each gated screen checks the session itself rather than trusting whoever
 * launched it. Relying on the entry point alone left the recents list as a way
 * straight into an already-unlocked settings screen.
 *
 * Subclasses build their UI as normal and override [onParentAuthenticated] for
 * anything that must wait until the parent is confirmed.
 *
 * Every gated screen also carries an explicit **Lock** button, pinned in the
 * corner regardless of what the subclass draws underneath. The five-minute
 * session exists so a parent can move between several rules screens without
 * retyping the PIN, which is also exactly the situation where walking away
 * mid-errand leaves the phone unlocked for those five minutes with nothing to
 * say so. A tap here ends the session immediately rather than waiting it out.
 */
abstract class ParentGatedActivity : DementorActivity() {

    private var awaitingPin = false
    private var lockButton: View? = null

    private val unlock = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        awaitingPin = false
        if (result.resultCode == Activity.RESULT_OK) {
            ParentSession.unlock()
            showGatedContent()
        } else {
            // Cancelled: there is nothing here for a non-parent to see.
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keeps rules and PIN entry out of the recents thumbnail and screenshots.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
    }

    /**
     * Wraps whatever a subclass sets as its content in a floating **Lock**
     * button, so every gated screen carries one without each subclass having to
     * remember to add it.
     */
    override fun setContentView(view: View) {
        val button = Button(this).apply {
            setText(R.string.session_lock)
            contentDescription = getString(R.string.session_lock_description)
            visibility = View.GONE
            setOnClickListener { lockAndGoHome() }
        }
        lockButton = button

        super.setContentView(
            FrameLayout(this).apply {
                layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
                addView(view, ViewGroup.LayoutParams(MATCH, MATCH))
                addView(
                    button,
                    FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply {
                        val margin = dimen(R.dimen.screen_padding)
                        setMargins(margin, margin, margin, margin)
                    },
                )
            },
        )
    }

    /** Ends the session right away and leaves nothing gated on screen behind it. */
    private fun lockAndGoHome() {
        ParentSession.lock()
        val home = when (RoleStore(this).roleOrChild) {
            DeviceRole.CHILD -> Intent(this, HomeActivity::class.java)
            DeviceRole.PARENT -> ParentHomeActivity.intent(this)
        }
        startActivity(home.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    override fun onResume() {
        super.onResume()
        when {
            !requiresPin() || ParentSession.isUnlocked -> {
                if (requiresPin()) ParentSession.touch()
                showGatedContent()
            }

            awaitingPin -> Unit

            else -> {
                hideGatedContent()
                awaitingPin = true
                unlock.launch(PinActivity.intent(this))
            }
        }
    }

    /**
     * Whether this particular visit needs the PIN at all.
     *
     * Unconditional for almost every gated screen — `true` by default. The one
     * exception ([JoinFamilyActivity]) has a state where there is nothing yet to
     * protect (a parent phone with no family configured), and demanding a PIN to
     * guard something that does not exist yet is a step for its own sake.
     */
    protected open fun requiresPin(): Boolean = true

    /** Called whenever the parent is confirmed, including after re-authenticating. */
    protected open fun onParentAuthenticated() = Unit

    /** Override to reveal the screen's content. */
    protected open fun onGatedContentVisible() = Unit

    /** Override to blank the screen while the parent is unverified. */
    protected open fun onGatedContentHidden() = Unit

    private fun showGatedContent() {
        onGatedContentVisible()
        onParentAuthenticated()
        // Nothing to lock on a screen requiresPin() left unguarded — there is no
        // session here to end early.
        lockButton?.visibility = if (requiresPin()) View.VISIBLE else View.GONE
    }

    private fun hideGatedContent() {
        onGatedContentHidden()
        lockButton?.visibility = View.GONE
    }
}
