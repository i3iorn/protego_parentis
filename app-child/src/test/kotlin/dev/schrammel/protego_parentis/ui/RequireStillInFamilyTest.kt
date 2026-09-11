package dev.schrammel.protego_parentis.ui

import android.app.Activity
import android.content.Intent
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.RoleStore
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.sync.RosterStore
import dev.schrammel.protego_parentis.sync.core.SyncRole
import dev.schrammel.protego_parentis.sync.core.Vouch
import dev.schrammel.protego_parentis.ui.rules.RuleTarget
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * #43's own done-when list: "a test covers the selected child being removed
 * from the roster mid-edit." A rule screen re-checks [RuleTargets.stillInFamily]
 * on every visit; this proves what happens when it fails — the screen leaves
 * rather than going on rendering (and offering to write) a ruleset that is no
 * longer anyone's.
 */
@RunWith(RobolectricTestRunner::class)
class RequireStillInFamilyTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private fun enrolChild(id: String) =
        RosterStore(context).enrol(Vouch(id = DeviceId(id), name = id, role = SyncRole.CHILD, publicKey = "key"))

    @After
    fun tearDown() {
        RosterStore(context).forgetAll()
        RoleStore(context).choose(DeviceRole.CHILD)
    }

    @Test
    fun `a target whose child is still in the family passes through`() {
        RoleStore(context).choose(DeviceRole.PARENT)
        enrolChild("CHILD1")
        val target = RuleTarget(DeviceId("CHILD1"), isOwnDevice = false)
        val activity = Robolectric.buildActivity(Activity::class.java, Intent()).setup().get()

        assertTrue(activity.requireStillInFamily(target))
        assertFalse(activity.isFinishing)
    }

    @Test
    fun `a target whose child was removed leaves the screen`() {
        RoleStore(context).choose(DeviceRole.PARENT)
        enrolChild("CHILD1")
        val target = RuleTarget(DeviceId("CHILD1"), isOwnDevice = false)
        RosterStore(context).revoke(DeviceId("CHILD1"))
        val activity = Robolectric.buildActivity(Activity::class.java, Intent()).setup().get()

        assertFalse(activity.requireStillInFamily(target))
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `this device's own target always passes, even off-roster`() {
        val target = RuleTarget(DeviceId("NOBODY"), isOwnDevice = true)
        val activity = Robolectric.buildActivity(Activity::class.java, Intent()).setup().get()

        assertTrue(activity.requireStillInFamily(target))
    }
}
