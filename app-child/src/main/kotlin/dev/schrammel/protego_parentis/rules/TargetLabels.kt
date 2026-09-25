package dev.schrammel.protego_parentis.rules

import android.content.Context
import dev.schrammel.protego_parentis.AppRegistry
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.model.GrantTarget
import dev.schrammel.protego_parentis.usage.InstalledApps

/**
 * What to call a [GrantTarget] on screen.
 *
 * Three screens and the audit log all needed the same answer, and each had grown
 * its own copy — which is how "Games" ends up in the history while the request
 * that created it says "category". One resolver, one answer.
 */
object TargetLabels {

    fun describe(context: Context, target: GrantTarget): String = when (target) {
        is GrantTarget.Device -> context.getString(R.string.requests_target_device)
        is GrantTarget.App -> InstalledApps(context).labelFor(target.pkg)
        is GrantTarget.Category ->
            AppRegistry.childStores.policy.categories
                .firstOrNull { it.id == target.id.value }
                ?.name
                ?: context.getString(R.string.requests_target_category)
    }
}
