package dev.schrammel.dementor_watch.rules

import android.content.Context
import dev.schrammel.dementor_watch.R
import dev.schrammel.dementor_watch.model.GrantTarget
import dev.schrammel.dementor_watch.usage.InstalledApps

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
            LocalPolicyStore(context).categories
                .firstOrNull { it.id == target.id.value }
                ?.name
                ?: context.getString(R.string.requests_target_category)
    }
}
