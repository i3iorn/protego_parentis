package dev.schrammel.protego_parentis.ui

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.TaskStackBuilder
import dev.schrammel.protego_parentis.enforcement.NotificationUx
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.RoleStore

/**
 * [NotificationUx], implemented here because building a `PendingIntent` for one
 * of this app's own screens is what `ui` is for.
 *
 * Every destination but two is built the same way: [pendingIntentFor] asks
 * Android's own `TaskStackBuilder` to walk `parentActivityName` back from the
 * manifest, so tapping "up" from a notification leads wherever tapping through
 * the app normally would. [openChat] and [openFamilyDevices] can't use that —
 * `parentActivityName` names one class, but a chat message or a roster change
 * can land on either kind of phone (#78) — so those two build the stack by hand,
 * rooted at whichever home this device actually has.
 */
class RealNotificationUx(context: Context) : NotificationUx {

    private val appContext = context.applicationContext

    override fun openHome(requestCode: Int) =
        pendingIntentFor(requestCode, Intent(appContext, HomeActivity::class.java))

    override fun openBlockedWhy(requestCode: Int, pkg: PackageName?): PendingIntent {
        // The child was mid-app when the block hit; the notification lands
        // on the blocked list for that app, not on a generic screen.
        return pendingIntentFor(requestCode, BlockedAppsActivity.intent(appContext, pkg))
    }

    override fun openRequestInbox(requestCode: Int) =
        pendingIntentFor(requestCode, RequestInboxActivity.intent(appContext))

    override fun openParentInbox(requestCode: Int) =
        pendingIntentFor(requestCode, ParentInboxActivity.intent(appContext))

    override fun openParentDevice(requestCode: Int) =
        pendingIntentFor(requestCode, ParentDeviceActivity.intent(appContext))

    override fun openAppControl(requestCode: Int) =
        pendingIntentFor(requestCode, AppControlActivity.intent(appContext))

    override fun openChat(requestCode: Int) =
        pendingIntentWithHomeRoot(requestCode, ChatActivity.intent(appContext))

    override fun openFamilyDevices(requestCode: Int) =
        pendingIntentWithHomeRoot(requestCode, FamilyDevicesActivity.intent(appContext))

    override fun openAudit(requestCode: Int) =
        pendingIntentFor(requestCode, AuditActivity.intent(appContext))

    override fun openRequests(requestCode: Int) =
        pendingIntentFor(requestCode, RequestsActivity.intent(appContext))

    override fun openFamilyDisbanded(requestCode: Int): PendingIntent {
        val destination = if (isParent()) {
            JoinFamilyActivity.intent(appContext)
        } else {
            Intent(appContext, HomeActivity::class.java)
        }
        return pendingIntentFor(requestCode, destination)
    }

    override fun openCelebration(requestCode: Int) =
        pendingIntentFor(requestCode, CelebrationActivity.intent(appContext))

    override fun openDiagnostics(requestCode: Int) =
        pendingIntentFor(requestCode, DiagnosticsActivity.intent(appContext))

    override fun isChatVisible(): Boolean = ChatActivity.isVisible

    private fun isParent() = RoleStore(appContext).roleOrChild == DeviceRole.PARENT

    /**
     * [destination]'s ancestors, from the manifest. Falls back to a plain intent
     * if the stack cannot be built, because a notification that opens the right
     * screen with the wrong back behaviour is still far better than one that
     * does not open.
     */
    private fun pendingIntentFor(requestCode: Int, destination: Intent): PendingIntent =
        runCatching {
            checkNotNull(
                TaskStackBuilder.create(appContext)
                    .addNextIntentWithParentStack(destination)
                    .getPendingIntent(requestCode, FLAGS),
            )
        }.getOrElse { plainPendingIntent(requestCode, destination) }

    /** As [pendingIntentFor], rooted at whichever home this device actually has. */
    private fun pendingIntentWithHomeRoot(requestCode: Int, destination: Intent): PendingIntent {
        val home = if (isParent()) {
            ParentHomeActivity.intent(appContext)
        } else {
            Intent(appContext, HomeActivity::class.java)
        }
        return runCatching {
            checkNotNull(
                TaskStackBuilder.create(appContext)
                    .addNextIntent(home)
                    .addNextIntent(destination)
                    .getPendingIntent(requestCode, FLAGS),
            )
        }.getOrElse { plainPendingIntent(requestCode, destination) }
    }

    private fun plainPendingIntent(requestCode: Int, destination: Intent): PendingIntent =
        PendingIntent.getActivity(
            appContext,
            requestCode,
            destination.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            FLAGS,
        )

    private companion object {
        const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    }
}
