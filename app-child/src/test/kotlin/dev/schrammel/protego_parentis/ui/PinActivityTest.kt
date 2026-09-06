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
 * `TYPE_NUMBER_VARIATION_PASSWORD` alone is not a reliable masking signal on
 * every keyboard — some OEM IMEs show the digit before obscuring it. Forcing
 * [PasswordTransformationMethod] on the view is what actually guarantees
 * masking, independent of the active keyboard.
 */
@RunWith(RobolectricTestRunner::class)
class PinActivityTest {

    @Test
    fun `the pin field is masked at the view layer, not only by input type`() {
        val activity = Robolectric.buildActivity(PinActivity::class.java).setup().get()

        val input = findEditText(activity.window.decorView)

        assertTrue(
            "the PIN field must force PasswordTransformationMethod",
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
