package dev.schrammel.protego_parentis.ui

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * A slider's spoken value: minutes, not the raw percentage a bare [SeekBar]
 * would report.
 */
@RunWith(RobolectricTestRunner::class)
class SliderAccessibilityTest {

    private val activity: Activity =
        Robolectric.buildActivity(Activity::class.java).setup().get()

    @Test
    fun `the delegate reports the formatted value as its text`() {
        val bar = SeekBar(activity)
        val info = AccessibilityNodeInfoCompat.obtain()

        accessibleValueDelegate { "30 minutes" }.onInitializeAccessibilityNodeInfo(bar, info)

        assertEquals("30 minutes", info.text)
        assertEquals("30 minutes", info.contentDescription)
    }

    @Test
    fun `a limit slider block installs an accessibility delegate`() {
        val block = activity.limitSliderBlock(30)

        assertNotNull(ViewCompat.getAccessibilityDelegate(findSeekBar(block)!!))
    }

    private fun findSeekBar(view: View): SeekBar? {
        if (view is SeekBar) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findSeekBar(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }
}
