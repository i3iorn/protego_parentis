package dev.schrammel.protego_parentis.enforcement

import android.app.Notification
import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * What a locked screen shows (#292): a family's chat, arrivals and requests
 * wait for an unlock whatever the device setting is; an emergency does not.
 */
@RunWith(RobolectricTestRunner::class)
class LockScreenVisibilityTest {

    private val context get() = RuntimeEnvironment.getApplication()
    private val notifier get() = RequestNotifier(context)
    private val manager get() = checkNotNull(context.getSystemService(NotificationManager::class.java))

    private fun latest(): Notification = shadowOf(manager).allNotifications.last()

    private fun Notification.allText(): String =
        listOfNotNull(
            extras.getCharSequence(Notification.EXTRA_TITLE),
            extras.getCharSequence(Notification.EXTRA_TEXT),
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
        ).joinToString(" ")

    @Test
    fun `a chat message shows nothing of itself on the lock screen`() {
        notifier.notifyChat("Mo", "the secret plan")

        val notification = latest()
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertNotNull(notification.publicVersion)
        assertFalse(notification.publicVersion.allText().contains("secret"))
        assertFalse(notification.publicVersion.allText().contains("Mo"))
    }

    @Test
    fun `an arrival shows no place name on the lock screen`() {
        notifier.notifyPlaceTransition("School", arrived = true)

        assertFalse(latest().publicVersion.allText().contains("School"))
    }

    @Test
    fun `an emergency stays readable on the lock screen`() {
        notifier.notifySos(moved = false)

        assertEquals(Notification.VISIBILITY_PUBLIC, latest().visibility)
    }
}
