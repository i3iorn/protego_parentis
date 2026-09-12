package dev.schrammel.protego_parentis.ui.rules

import android.app.Activity
import android.content.Intent
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.RoleStore
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.security.LocalDeviceId
import dev.schrammel.protego_parentis.sync.RosterStore
import dev.schrammel.protego_parentis.sync.core.SyncRole
import dev.schrammel.protego_parentis.sync.core.Vouch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * #43: which ruleset a rules screen edits, resolved the same way on both
 * kinds of phone. The one rule that must hold regardless of everything else
 * here: a family with a single managed phone never needs a [RuleScope]
 * naming it explicitly, and a family with a second one is never guessed at.
 */
@RunWith(RobolectricTestRunner::class)
class RuleScopeTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private fun activity(extraChildId: String? = null): Activity {
        val intent = Intent()
        RuleTargets.putExtra(intent, extraChildId?.let(::DeviceId))
        return Robolectric.buildActivity(Activity::class.java, intent).setup().get()
    }

    private fun enrolChild(id: String) =
        RosterStore(context).enrol(Vouch(id = DeviceId(id), name = id, role = SyncRole.CHILD, publicKey = "key"))

    @After
    fun tearDown() {
        LocalDeviceId.useInTests(null)
        RosterStore(context).forgetAll()
        RoleStore(context).choose(DeviceRole.CHILD)
    }

    @Test
    fun `the managed phone always resolves to itself, regardless of any extra`() {
        RoleStore(context).choose(DeviceRole.CHILD)
        LocalDeviceId.useInTests("THISPHONE")

        val target = RuleTargets.resolve(activity(extraChildId = "SOMEONE_ELSE"))

        assertEquals(DeviceId("THISPHONE"), target?.childId)
        assertTrue(target?.isOwnDevice == true)
    }

    @Test
    fun `a parent's phone with one child resolves to it without an extra`() {
        RoleStore(context).choose(DeviceRole.PARENT)
        enrolChild("CHILD1")

        val target = RuleTargets.resolve(activity())

        assertEquals(DeviceId("CHILD1"), target?.childId)
        assertTrue(target?.isOwnDevice == false)
    }

    @Test
    fun `a parent's phone with two children and no extra resolves to null, forcing the picker`() {
        RoleStore(context).choose(DeviceRole.PARENT)
        enrolChild("CHILD1")
        enrolChild("CHILD2")

        assertNull(RuleTargets.resolve(activity()))
    }

    @Test
    fun `a parent's phone with an explicit extra resolves to that child`() {
        RoleStore(context).choose(DeviceRole.PARENT)
        enrolChild("CHILD1")
        enrolChild("CHILD2")

        val target = RuleTargets.resolve(activity(extraChildId = "CHILD2"))

        assertEquals(DeviceId("CHILD2"), target?.childId)
    }
}
