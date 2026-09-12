package dev.schrammel.protego_parentis.ui

import android.app.Activity
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.ui.rules.RuleScope
import dev.schrammel.protego_parentis.ui.rules.RuleTargets

/**
 * The mid-edit-removal check every rule screen makes on every visit: a
 * parent could be standing on a screen naming a child that a different
 * parent phone has since removed from the family. `true` means keep
 * rendering as normal; `false` means this call has already said so and
 * left the screen — the caller's cue to stop rendering and return.
 */
internal fun Activity.requireStillInFamily(target: RuleScope): Boolean {
    if (RuleTargets.stillInFamily(this, target)) return true
    toast(R.string.rules_target_gone)
    finish()
    return false
}
