package dev.schrammel.protego_parentis.ui

import android.os.Bundle
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.RoleStore

/**
 * Base class for the screens that only exist on a parent's own phone.
 *
 * These are not PIN-gated, and that is deliberate: a parent's handset is already
 * behind its own lock screen, and a second PIN in front of "approve 15 minutes"
 * is friction with nothing on the other side of it. What protects them is that
 * the phone is not the managed one.
 *
 * So each screen checks that for itself, rather than trusting whoever launched
 * it — the same rule [ParentGatedActivity] follows, and for the same reason. No
 * path on a child device reaches these today, but "no path today" is not a
 * property that survives a year of edits, and the failure it protects against is
 * a child approving their own requests.
 */
abstract class ParentRoleActivity : DementorActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isParentPhone()) {
            finish()
            return
        }
        onParentPhoneReady(savedInstanceState)
    }

    /** Replaces [onCreate] for subclasses; only runs on a parent's phone. */
    protected abstract fun onParentPhoneReady(savedInstanceState: Bundle?)

    override fun onResume() {
        super.onResume()
        // Re-checked because the role can change while the screen is in the
        // background: switching this phone to the managed one must not leave an
        // inbox sitting in the recents list, one tap from approving something.
        if (!isParentPhone()) {
            finish()
            return
        }
        onParentPhoneResumed()
    }

    /** Replaces [onResume] for subclasses; only runs on a parent's phone. */
    protected open fun onParentPhoneResumed() = Unit

    private fun isParentPhone(): Boolean =
        RoleStore(this).roleOrChild == DeviceRole.PARENT
}
