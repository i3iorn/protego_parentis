package dev.schrammel.protego_parentis.enforcement

import android.app.Notification
import android.app.NotificationManager
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.RoleStore
import dev.schrammel.protego_parentis.ui.AuditActivity
import dev.schrammel.protego_parentis.ui.ChatActivity
import dev.schrammel.protego_parentis.ui.FamilyDevicesActivity
import dev.schrammel.protego_parentis.ui.HomeActivity
import dev.schrammel.protego_parentis.ui.ParentHomeActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * #78 part 4: a chat message or a family-roster change can land on either
 * kind of phone, so the notification announcing it has to be rooted at
 * *this* phone's actual home when tapped — `parentActivityName` in the
 * manifest can only name one class, and these two genuinely have two. This
 * pins [RequestNotifier]'s hand-built stack for both, from the outside, the
 * same way tapping the notification itself would exercise it.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationBackStackTest {

    private val context get() = RuntimeEnvironment.getApplication()
    private val notifier get() = RequestNotifier(context)
    private val manager get() = checkNotNull(context.getSystemService(NotificationManager::class.java))

    @After
    fun tearDown() = RoleStore(context).choose(DeviceRole.CHILD)

    @Test
    fun `a chat notification on the managed phone is rooted at its own home`() {
        RoleStore(context).choose(DeviceRole.CHILD)

        notifier.notifyChat("Mo", "hi")

        assertStack(latestNotification(), HomeActivity::class.java, ChatActivity::class.java)
    }

    @Test
    fun `a chat notification on a parent's phone is rooted at the parent's dashboard`() {
        RoleStore(context).choose(DeviceRole.PARENT)

        notifier.notifyChat("Riley", "hi")

        assertStack(latestNotification(), ParentHomeActivity::class.java, ChatActivity::class.java)
    }

    @Test
    fun `a family-devices notification on the managed phone is rooted at its own home`() {
        RoleStore(context).choose(DeviceRole.CHILD)

        notifier.notifyDevicePending()

        assertStack(latestNotification(), HomeActivity::class.java, FamilyDevicesActivity::class.java)
    }

    @Test
    fun `a family-devices notification on a parent's phone is rooted at the parent's dashboard`() {
        RoleStore(context).choose(DeviceRole.PARENT)

        notifier.notifyDevicePending()

        assertStack(latestNotification(), ParentHomeActivity::class.java, FamilyDevicesActivity::class.java)
    }

    /**
     * Everything else still comes straight from the manifest, unchanged. A
     * lock notification only ever fires on the managed phone itself, so even
     * with a parent's role stored locally in this process the root stays the
     * managed phone's own home — it is the manifest chain, not the role,
     * doing the routing here.
     */
    @Test
    fun `a lock notification is rooted at the managed phone's own home`() {
        RoleStore(context).choose(DeviceRole.PARENT)

        notifier.notifyLocked()

        assertStack(latestNotification(), HomeActivity::class.java, AuditActivity::class.java)
    }

    private fun latestNotification(): Notification =
        checkNotNull(shadowOf(manager).allNotifications.lastOrNull()) { "no notification was posted" }

    private fun assertStack(notification: Notification, vararg expected: Class<*>) {
        val stack = shadowOf(checkNotNull(notification.contentIntent)).savedIntents
        assertEquals(expected.map { it.name }, stack.map { it.component?.className })
    }
}
