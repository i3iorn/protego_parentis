package dev.schrammel.protego_parentis.ui

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The reason a child types for a time request is real, composed input — losing
 * it to an incidental rotation is the same failure as losing a half-written
 * email. [RequestsActivity.reasonInput] used to have no id at all, which meant
 * Android's own automatic view-state save/restore (keyed by id) had nothing to
 * key on and silently did nothing.
 */
@RunWith(RobolectricTestRunner::class)
class RequestsActivityRotationTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `the reason typed so far survives a rotation`() {
        val controller = Robolectric.buildActivity(RequestsActivity::class.java, RequestsActivity.intent(context))
        val activity = controller.setup().get()

        findEditText(activity.window.decorView)!!.setText("Homework is done")

        controller.recreate()
        val recreated = controller.get()

        assertEquals("Homework is done", findEditText(recreated.window.decorView)!!.text.toString())
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
