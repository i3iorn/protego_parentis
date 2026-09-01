package dev.schrammel.protego_parentis.policy.reconcile

/** What locking the screen actually did. */
data class LockResult(
    val locked: Boolean,
    /**
     * True when the screen went dark but nothing was locked, because the phone has
     * no PIN, pattern or password of its own.
     *
     * Reported rather than swallowed: a parent who presses "lock now" and is told
     * it worked, when in fact the screen swipes straight back open, has been told
     * something untrue about their child's phone.
     */
    val hadNoLockScreen: Boolean = false,
    val skippedReason: String? = null,
)

/**
 * Locks the child's screen on demand.
 *
 * A one-shot command, and deliberately not part of [PolicyReconciler]. The
 * reconciler is a desired-state differ: it reads what the device reports, acts
 * only on the difference, and is safe to run every few seconds precisely because
 * an unchanged policy costs nothing. A lock is an edge, not a level — there is no
 * "is it locked" to diff against, so modelling it as state would either re-lock
 * the phone on every tick or require the reconciler to hold a latch, which is the
 * kind of decision it is not allowed to hold.
 *
 * [DeviceReleaser] is the existing precedent for exactly this shape.
 */
class DeviceLocker(private val port: DevicePolicyPort) {

    fun lock(): LockResult {
        if (!port.isDeviceOwner()) {
            // Monitor-only, or ownership was lost. Nothing can be locked, and
            // saying so is better than a button that appears to work.
            return LockResult(locked = false, skippedReason = "not device owner")
        }

        val secure = port.hasSecureLockScreen()
        port.lockScreen()
        return LockResult(locked = true, hadNoLockScreen = !secure)
    }
}
