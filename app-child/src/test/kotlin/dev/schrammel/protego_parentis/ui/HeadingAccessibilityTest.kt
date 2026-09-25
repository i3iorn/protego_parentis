package dev.schrammel.protego_parentis.ui

import android.app.Activity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Every screen is built in code from these two helpers, so they are the only
 * place TalkBack's heading navigation can learn where a screen's parts begin.
 */
@RunWith(RobolectricTestRunner::class)
class HeadingAccessibilityTest {

    private val activity: Activity =
        Robolectric.buildActivity(Activity::class.java).setup().get()

    @Test
    fun `a screen title is an accessibility heading`() {
        assertTrue(activity.screenTitle("Rules").isAccessibilityHeading)
    }

    @Test
    fun `a section header is an accessibility heading`() {
        assertTrue(activity.sectionHeader("Apps").isAccessibilityHeading)
    }
}
