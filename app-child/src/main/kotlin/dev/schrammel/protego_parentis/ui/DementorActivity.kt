package dev.schrammel.protego_parentis.ui

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * The base every screen in this app sits on.
 *
 * Its one job is window insets. From Android 15 an app targeting a recent SDK is
 * drawn edge to edge whether it asks to be or not, so the status bar and the
 * gesture bar sit *on top of* the content unless every screen accounts for them.
 * Doing that per screen means forgetting it on the next one, so it happens here:
 * anything passed to [setContentView] is padded automatically.
 *
 * Padding is added to whatever the view already had rather than replacing it, and
 * the display cutout is included so a screen is not clipped by a camera notch in
 * landscape.
 */
abstract class DementorActivity : AppCompatActivity() {

    /**
     * Whether this screen should also move out of the keyboard's way.
     *
     * Off by default, because every other screen in this app is a scroll view: the
     * keyboard covering the bottom of a scrolling column is fine, since the field
     * being typed into scrolls itself into view. It is only a problem where
     * something is pinned to the bottom and has to stay reachable — which is the
     * chat composer, and nothing else.
     */
    protected open val movesForKeyboard: Boolean = false

    override fun setContentView(view: View) {
        super.setContentView(view)
        view.padForSystemBars(movesForKeyboard)
    }

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        findViewById<View>(android.R.id.content)
            ?.let { (it as? android.view.ViewGroup)?.getChildAt(0) ?: it }
            ?.padForSystemBars(movesForKeyboard)
    }
}

/**
 * Keeps the view clear of the system bars.
 *
 * The view's own padding at the time of the call is treated as the baseline; the
 * inset is added on top. Without capturing that baseline first, a screen would
 * lose its own padding the moment the insets arrived — or gain it twice if they
 * arrived more than once, which they do on rotation and when the keyboard opens.
 */
internal fun View.padForSystemBars(followKeyboard: Boolean = false) {
    val original = Insets.of(paddingLeft, paddingTop, paddingRight, paddingBottom)

    ViewCompat.setOnApplyWindowInsetsListener(this) { view, windowInsets ->
        val bars = windowInsets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        // The larger of the two, never the sum. The keyboard is drawn over the
        // navigation bar, so adding both lifts the content by a gesture bar's
        // height of empty space above the keys.
        val bottom = if (followKeyboard) {
            maxOf(bars.bottom, windowInsets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
        } else {
            bars.bottom
        }
        view.setPadding(
            original.left + bars.left,
            original.top + bars.top,
            original.right + bars.right,
            original.bottom + bottom,
        )
        windowInsets
    }
    ViewCompat.requestApplyInsets(this)
}
