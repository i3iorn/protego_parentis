package dev.schrammel.dementor_watch.security

import android.content.Context
import androidx.core.content.edit

/**
 * Which side of the family this install is.
 *
 * One APK, two jobs. A second app would have meant duplicating the sync layer,
 * the crypto, the request model and the design system to gain nothing a switch
 * does not already give.
 */
enum class DeviceRole {
    /** The phone under management: usage, enforcement, the child's dashboard. */
    CHILD,

    /**
     * A parent's own phone. Watches the family topic, answers requests and grants
     * bonus time. It collects no usage and enforces nothing — there is no child
     * on this device to enforce against.
     */
    PARENT,
}

/**
 * Remembers the role, and refuses to let it change quietly.
 *
 * The role is the boundary the whole product rests on: a child who could flip
 * their own phone to parent mode could approve their own requests. So switching
 * is gated behind the parent PIN everywhere it is offered.
 *
 * The *first* choice cannot be gated — there is no PIN yet on a fresh install.
 * That is the same trust boundary setup has always had: whoever holds the phone
 * during setup is assumed to be the parent.
 */
class RoleStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** Null until someone has been asked. */
    val role: DeviceRole?
        get() = prefs.getString(KEY_ROLE, null)
            ?.let { stored -> DeviceRole.entries.firstOrNull { it.name == stored } }

    /** What to assume before the question has been answered. */
    val roleOrChild: DeviceRole
        get() = role ?: DeviceRole.CHILD

    val isParent: Boolean
        get() = role == DeviceRole.PARENT

    fun choose(role: DeviceRole) {
        prefs.edit { putString(KEY_ROLE, role.name) }
    }

    private companion object {
        const val FILE_NAME = "device_role"
        const val KEY_ROLE = "role"
    }
}
