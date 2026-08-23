package dev.schrammel.dementor_watch.ui

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import dev.schrammel.dementor_watch.security.ParentSession

/**
 * Base class for every screen only a parent may see.
 *
 * Each gated screen checks the session itself rather than trusting whoever
 * launched it. Relying on the entry point alone left the recents list as a way
 * straight into an already-unlocked settings screen.
 *
 * Subclasses build their UI as normal and override [onParentAuthenticated] for
 * anything that must wait until the parent is confirmed.
 */
abstract class ParentGatedActivity : AppCompatActivity() {

    private var awaitingPin = false

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

    override fun onResume() {
        super.onResume()
        when {
            ParentSession.isUnlocked -> {
                ParentSession.touch()
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

    /** Called whenever the parent is confirmed, including after re-authenticating. */
    protected open fun onParentAuthenticated() = Unit

    /** Override to reveal the screen's content. */
    protected open fun onGatedContentVisible() = Unit

    /** Override to blank the screen while the parent is unverified. */
    protected open fun onGatedContentHidden() = Unit

    private fun showGatedContent() {
        onGatedContentVisible()
        onParentAuthenticated()
    }

    private fun hideGatedContent() {
        onGatedContentHidden()
    }
}
