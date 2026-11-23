package dev.schrammel.protego_parentis.ui

import android.app.Activity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

/**
 * The keyboard contract (P-feedback): a focused text field must be visible
 * above the keyboard. `adjustResize` shrinks the window; `keepFieldsVisible`
 * is what then scrolls the field back into view — this pins the scroll math.
 */
@RunWith(RobolectricTestRunner::class)
class FieldVisibilityTest {

    private val activity: Activity =
        Robolectric.buildActivity(Activity::class.java).setup().get()

    private fun tallColumn(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        // Filler tall enough that a field at the bottom is far off-screen.
        repeat(12) { addView(View(activity).apply { minimumHeight = 500 }) }
        addView(EditText(activity).apply { setHint("field") })
    }

    @Test
    fun `focusing a low field scrolls it into view`() {
        val scroll = ScrollView(activity).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(1000, 1000)
            addView(
                tallColumn(),
                LinearLayout.LayoutParams(1000, android.view.ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            keepFieldsVisible()
        }
        activity.setContentView(scroll)
        // Give the hierarchy real measurements; Robolectric lays out on demand.
        scroll.layout(0, 0, 1000, 1000)
        scroll.getChildAt(0).layout(0, 0, 1000, 6500)

        val field = requireNotNull(descendantEditText(scroll)) { "no field under the scroll view" }
        field.requestFocus()
        // The focus listener posts its scroll; drain the looper.
        ShadowLooper.idleMainLooper()

        assertTrue(
            "the focused field must be scrolled into view (scrollY=${scroll.scrollY})",
            scroll.scrollY > 0,
        )
    }

    @Test
    fun `a field already visible is not scrolled`() {
        val scroll = ScrollView(activity).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(1000, 1000)
            addView(
                LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(EditText(activity))
                },
                LinearLayout.LayoutParams(1000, android.view.ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            keepFieldsVisible()
        }
        activity.setContentView(scroll)
        scroll.layout(0, 0, 1000, 1000)
        scroll.getChildAt(0).layout(0, 0, 1000, 1500)

        requireNotNull(descendantEditText(scroll)).requestFocus()
        ShadowLooper.idleMainLooper()

        assertEquals("a visible field must not scroll", 0, scroll.scrollY)
    }

    private fun descendantEditText(view: View): EditText? {
        if (view is EditText) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                descendantEditText(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }
}
