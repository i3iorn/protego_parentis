package dev.schrammel.protego_parentis.ui

import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * #69: a report that every digit typed into a PIN field was visible.
 *
 * The cause was ordering, not the keyboard. A password input type does install
 * [PasswordTransformationMethod], but `setSingleLine(true)` then replaces the
 * transformation with `SingleLineTransformationMethod` — so the field rendered
 * its contents in the clear on every device, whatever the IME did. This pins
 * the end state rather than the call order, so the field stays masked however
 * the builder is later rearranged.
 */
@RunWith(RobolectricTestRunner::class)
class PinActivityTest {

    @Test
    fun `the pin field is masked at the view layer, not only by input type`() {
        val activity = Robolectric.buildActivity(PinActivity::class.java).setup().get()

        val input = findEditText(activity.window.decorView)

        assertTrue(
            "the PIN field must end up with PasswordTransformationMethod, not " +
                "whatever setSingleLine last installed",
            input?.transformationMethod is PasswordTransformationMethod,
        )
    }

    private fun findEditText(view: View): EditText? {
        if (view is EditText) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findEditText(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }
}
