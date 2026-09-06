package dev.schrammel.protego_parentis.ui

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.RoleStore
import dev.schrammel.protego_parentis.sync.DeviceId
import dev.schrammel.protego_parentis.sync.RosterStore
import dev.schrammel.protego_parentis.sync.SyncRole
import dev.schrammel.protego_parentis.sync.Vouch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * #71: the two doors into [PlacesActivity] — this screen's "Places" button
 * and [ContextRulesActivity]'s "Manage places" — used to diverge, one always
 * local by fiat and one target-aware. This pins that both now resolve to the
 * *same* target, by decoding what each door actually hands [PlacesActivity]
 * through the same [RuleTargets.resolve] the receiving screen itself uses.
 */
@RunWith(RobolectricTestRunner::class)
class PlaceAlertsActivityTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private fun enrolChild(id: String) =
        RosterStore(context).enrol(Vouch(id = DeviceId(id), name = id, role = SyncRole.CHILD, publicKey = "key"))

    @After
    fun tearDown() {
        RoleStore(context).choose(DeviceRole.CHILD)
        RosterStore(context).forgetAll()
    }

    @Test
    fun `its Places button hands on the same target it was opened with`() {
        RoleStore(context).choose(DeviceRole.PARENT)
        enrolChild("CHILD1")
        val activity = Robolectric.buildActivity(
            PlaceAlertsActivity::class.java,
            PlaceAlertsActivity.intent(context, DeviceId("CHILD1")),
        ).setup().get()

        findButton(activity.window.decorView, activity.getString(R.string.places_title)).performClick()

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(PlacesActivity::class.java.name, started?.component?.className)

        val downstream = Robolectric.buildActivity(Activity::class.java, started).setup().get()
        val resolvedTarget = RuleTargets.resolve(downstream)
        assertTrue(resolvedTarget?.isOwnDevice == false)
        assertEquals(DeviceId("CHILD1"), resolvedTarget?.childId)
    }

    private fun findButton(view: View, text: String): Button {
        if (view is Button && view.text == text) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                runCatching { findButton(view.getChildAt(i), text) }.getOrNull()?.let { return it }
            }
        }
        error("no button titled \"$text\" was rendered")
    }
}
