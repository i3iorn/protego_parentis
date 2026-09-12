package dev.schrammel.protego_parentis.ui.rules

import android.app.Activity
import android.content.Context
import android.content.Intent
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.RoleStore
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.security.LocalDeviceId
import dev.schrammel.protego_parentis.sync.RosterStore

/**
 * Which ruleset a rules screen is pointed at, and whether that happens to be
 * this device's own.
 *
 * [isOwnDevice] is not derivable from `childId == LocalDeviceId.current()`
 * everywhere it is needed — a managed phone reads it before deciding which
 * store to construct in the first place — so it travels alongside the id
 * rather than being recomputed at every use.
 */
data class RuleScope(val childId: DeviceId, val isOwnDevice: Boolean)

/**
 * Resolves and threads [RuleScope] through the rule screens.
 *
 * A managed phone always edits itself — the [Intent] extra is never even
 * read on that branch — which is also why a family with one managed phone
 * never sees anything hinting a second could exist: nothing here invents a
 * picker for that case, one is only ever launched explicitly (see
 * `RulesEntry`) when the roster actually holds more than one child.
 */
object RuleTargets {
    private const val EXTRA_CHILD_ID = "ruleTargetChildId"

    /** Carries [childId] forward onto an [Intent] to a sibling rule screen. */
    fun putExtra(intent: Intent, childId: DeviceId?): Intent =
        childId?.let { intent.putExtra(EXTRA_CHILD_ID, it.value) } ?: intent

    /**
     * The ruleset [activity] should edit, or null if a parent's phone has more
     * than one managed phone and none was named — the caller's cue to show
     * [RulesetPickerActivity] instead of guessing.
     */
    fun resolve(activity: Activity): RuleScope? {
        val requested = activity.intent.getStringExtra(EXTRA_CHILD_ID)?.let(::DeviceId)
        val ownId = LocalDeviceId.current()?.let(::DeviceId)

        if (RoleStore(activity).roleOrChild == DeviceRole.CHILD && ownId != null) {
            return RuleScope(ownId, isOwnDevice = true)
        }
        if (requested != null) {
            return RuleScope(requested, isOwnDevice = requested == ownId)
        }
        return RosterStore(activity).children().singleOrNull()?.let { RuleScope(it.id, isOwnDevice = false) }
    }

    /** Whether [target] is still a device this phone's family actually has. */
    fun stillInFamily(context: Context, target: RuleScope): Boolean =
        target.isOwnDevice || RosterStore(context).children().any { it.id == target.childId }
}
