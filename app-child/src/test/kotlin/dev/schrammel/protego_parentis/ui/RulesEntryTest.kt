package dev.schrammel.protego_parentis.ui

import android.app.Activity
import dev.schrammel.protego_parentis.sync.DeviceId
import dev.schrammel.protego_parentis.sync.RosterStore
import dev.schrammel.protego_parentis.sync.SyncRole
import dev.schrammel.protego_parentis.sync.Vouch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * #43: "the picker appears when a second phone is enrolled, and not before" —
 * the one behaviour [RulesEntry] exists to guarantee.
 */
@RunWith(RobolectricTestRunner::class)
class RulesEntryTest {

    private val context get() = RuntimeEnvironment.getApplication()
    private val activity: Activity get() = Robolectric.buildActivity(Activity::class.java).setup().get()

    private fun enrolChild(id: String) =
        RosterStore(context).enrol(Vouch(id = DeviceId(id), name = id, role = SyncRole.CHILD, publicKey = "key"))

    @After
    fun tearDown() = RosterStore(context).forgetAll()

    @Test
    fun `with no children, launches RulesActivity with no target extra`() {
        RulesEntry.launch(activity)

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(RulesActivity::class.java.name, started.component?.className)
    }

    @Test
    fun `with one child, launches RulesActivity directly - never the picker`() {
        enrolChild("CHILD1")

        RulesEntry.launch(activity)

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(RulesActivity::class.java.name, started.component?.className)
    }

    @Test
    fun `with two children, launches the picker instead of guessing`() {
        enrolChild("CHILD1")
        enrolChild("CHILD2")

        RulesEntry.launch(activity)

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(RulesetPickerActivity::class.java.name, started.component?.className)
    }
}
