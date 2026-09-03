package dev.schrammel.protego_parentis.ui

import android.os.Bundle
import android.widget.TextView
import dev.schrammel.protego_parentis.security.ParentSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * #43: a rules screen that opts into [ParentGatedActivity.supportsReadOnly]
 * has to render before the PIN is entered — showing what it governs, never
 * a way to change it — instead of the ordinary PIN-or-nothing gate. This is
 * the "a screen opened against a ruleset this device may not write offers
 * no way to write it" test the milestone's done-when list asks for, proven
 * at the one place every rule screen shares the mechanism rather than
 * repeated per screen.
 */
@RunWith(RobolectricTestRunner::class)
class ParentGatedActivityReadOnlyTest {

    private class ReadOnlyScreen : ParentGatedActivity() {
        var visibleCalls = 0
        var hiddenCalls = 0

        override fun supportsReadOnly() = true

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContentView(TextView(this))
        }

        override fun onGatedContentVisible() {
            visibleCalls++
        }

        override fun onGatedContentHidden() {
            hiddenCalls++
        }

        fun triggerRequestUnlock() = requestUnlock()

        fun editableNow() = canEdit
    }

    private class OrdinaryGatedScreen : ParentGatedActivity() {
        var visibleCalls = 0

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContentView(TextView(this))
        }

        override fun onGatedContentVisible() {
            visibleCalls++
        }
    }

    @Before
    fun lockSession() = ParentSession.lock()

    @After
    fun tearDown() = ParentSession.lock()

    @Test
    fun `a read-only screen renders content without a PIN prompt while locked`() {
        val activity = Robolectric.buildActivity(ReadOnlyScreen::class.java).setup().get()

        assertEquals(1, activity.visibleCalls)
        assertFalse(activity.editableNow())
        assertNull("no PIN screen should have been launched", shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `an ordinary gated screen still launches the PIN prompt while locked`() {
        val activity = Robolectric.buildActivity(OrdinaryGatedScreen::class.java).setup().get()

        assertEquals(0, activity.visibleCalls)
        assertTrue(
            "the PIN screen should have been launched",
            shadowOf(activity).nextStartedActivity?.component?.className == PinActivity::class.java.name,
        )
    }

    @Test
    fun `once unlocked, a read-only screen becomes editable`() {
        ParentSession.unlock()
        val activity = Robolectric.buildActivity(ReadOnlyScreen::class.java).setup().get()

        assertTrue(activity.editableNow())
    }

    @Test
    fun `requestUnlock launches the PIN screen from a read-only visit`() {
        val activity = Robolectric.buildActivity(ReadOnlyScreen::class.java).setup().get()
        shadowOf(activity).clearNextStartedActivities()

        activity.triggerRequestUnlock()

        assertTrue(
            "requestUnlock should have launched the PIN screen",
            shadowOf(activity).nextStartedActivity?.component?.className == PinActivity::class.java.name,
        )
    }
}
