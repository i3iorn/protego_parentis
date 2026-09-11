package dev.schrammel.protego_parentis.enforcement

import android.app.PendingIntent

/**
 * Wherever a notification needs to open a specific screen, without enforcement
 * (or sync) needing to import that screen to do it.
 *
 * [RequestNotifier] and [GoalNotifier] used to import ten distinct `ui`
 * activities between them just to build the `PendingIntent` each notification
 * opens, plus the back-stack logic (see the old `RequestNotifier.stackFor`) for
 * getting the "up" button to lead somewhere sensible, and which of two possible
 * screens a disbanded family should reopen to. All three are UI concerns that
 * `ui`, not `enforcement`, should own.
 *
 * [requestCode] is the caller's own notification id, threaded through so two
 * different notifications that happen to open the same screen still get
 * independent `PendingIntent`s — matching what direct `PendingIntent.getActivity`
 * calls with distinct ids already gave for free before this existed.
 */
interface NotificationUx {
    /** The managed phone's own home screen. */
    fun openHome(requestCode: Int): PendingIntent

    /** The managed phone's own, PIN-gated, request inbox. */
    fun openRequestInbox(requestCode: Int): PendingIntent

    /** A parent's inbox of requests raised by the managed phone. */
    fun openParentInbox(requestCode: Int): PendingIntent

    /** A parent's screen for one managed device. */
    fun openParentDevice(requestCode: Int): PendingIntent

    /** The managed phone's own, PIN-gated, app review screen. */
    fun openAppControl(requestCode: Int): PendingIntent

    /** The family conversation. */
    fun openChat(requestCode: Int): PendingIntent

    /** The list of devices in the family, and any awaiting approval. */
    fun openFamilyDevices(requestCode: Int): PendingIntent

    /** The audit log. */
    fun openAudit(requestCode: Int): PendingIntent

    /** Every request this device has raised, and how each was answered. */
    fun openRequests(requestCode: Int): PendingIntent

    /**
     * Wherever this phone should land after its family disbanded — its own
     * home if it is the managed phone, or rejoining another family if it is a
     * parent's.
     */
    fun openFamilyDisbanded(requestCode: Int): PendingIntent

    /** The earned-prize celebration screen. */
    fun openCelebration(requestCode: Int): PendingIntent

    /** Whether the chat screen is the thing currently on screen. */
    fun isChatVisible(): Boolean
}
