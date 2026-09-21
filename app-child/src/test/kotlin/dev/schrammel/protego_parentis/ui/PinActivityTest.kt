package dev.schrammel.protego_parentis.ui

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import dev.schrammel.protego_parentis.security.PinLock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast

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

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        context.getSharedPreferences("security", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("pin_attempts", Context.MODE_PRIVATE).edit().clear().commit()
    }

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

    /**
     * [PinActivity.verify]/[PinActivity.confirmNew] moved [PinLock]'s PBKDF2
     * hashing and [dev.schrammel.protego_parentis.security.PinAttempts]'s
     * synchronous commits off the UI thread and onto [Background]'s own
     * worker — see their own doc comments for why. These drive the real
     * submit flow end to end and poll for the result the same way
     * [AppPickerActivityTest] already does for a screen whose async work
     * Robolectric does not control.
     */
    @Test
    fun `choosing and confirming a first PIN commits it off the UI thread and finishes ok`() {
        val activity = Robolectric.buildActivity(PinActivity::class.java).setup().get()

        findEditText(activity.window.decorView)?.setText("123456")
        clickButton(activity, "Continue")

        // Now on CONFIRM_NEW.
        findEditText(activity.window.decorView)?.setText("123456")
        clickButton(activity, "Continue")

        assertTrue(
            "the activity should finish once the PIN is stored",
            awaitTrue { activity.isFinishing },
        )
        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        assertTrue(PinLock(RuntimeEnvironment.getApplication()).isSet)
    }

    @Test
    fun `an existing PIN entered wrong is checked off the UI thread and refused`() {
        PinLock(RuntimeEnvironment.getApplication()).set("123456")
        val activity = Robolectric.buildActivity(PinActivity::class.java).setup().get()

        findEditText(activity.window.decorView)?.setText("000000")
        clickButton(activity, "Continue")

        assertTrue(
            "the wrong-PIN toast should appear once the background check completes",
            awaitTrue { ShadowToast.getTextOfLatestToast() == "That PIN is not right." },
        )
        assertFalse(activity.isFinishing)
    }

    private fun clickButton(activity: Activity, text: String) {
        findAll(activity.window.decorView) { it is Button && it.text.toString() == text }
            .filterIsInstance<Button>()
            .first()
            .performClick()
    }

    private fun findAll(view: View, matches: (View) -> Boolean): List<View> {
        val result = mutableListOf<View>()
        if (matches(view)) result += view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) result += findAll(view.getChildAt(i), matches)
        }
        return result
    }

    /** Polls the paused main looper and [condition] until it is true, or gives up. */
    private fun awaitTrue(condition: () -> Boolean): Boolean {
        val main = shadowOf(Looper.getMainLooper())
        repeat(ATTEMPTS) {
            main.idle()
            if (condition()) return true
            Thread.sleep(POLL_MILLIS)
        }
        main.idle()
        return condition()
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

    private companion object {
        const val ATTEMPTS = 100
        const val POLL_MILLIS = 10L
    }
}
