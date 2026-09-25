package dev.schrammel.protego_parentis.rules

import dev.schrammel.protego_parentis.model.PackageName

/**
 * The well-known ids [RuleMigration] gives each legacy field it folds into a
 * [dev.schrammel.protego_parentis.model.ContextRule].
 *
 * Writing them (the migration), finding them again (the screens that edit one
 * specific migrated rule) and parsing them back (the editor that must not widen
 * a single-app rule) all have to agree on the exact string, or a screen edits
 * a rule the migration never wrote. They used to spell it out separately.
 */
object MigratedRuleIds {

    fun schedule(scheduleId: String): String = LocalPolicyStore.MIGRATED_SCHEDULE_ID_PREFIX + scheduleId

    fun exempt(pkg: PackageName): String = LocalPolicyStore.MIGRATED_EXEMPT_ID_PREFIX + pkg.value

    fun appBudget(pkg: PackageName): String = LocalPolicyStore.MIGRATED_APP_BUDGET_ID_PREFIX + pkg.value

    fun appBlock(pkg: PackageName): String = LocalPolicyStore.MIGRATED_APP_BLOCK_ID_PREFIX + pkg.value

    fun categoryBudget(categoryId: String): String =
        LocalPolicyStore.MIGRATED_CATEGORY_BUDGET_ID_PREFIX + categoryId

    /** The one app an [appBudget] or [appBlock] id names, or null for any other id. */
    fun singleAppOf(ruleId: String): PackageName? =
        listOf(LocalPolicyStore.MIGRATED_APP_BUDGET_ID_PREFIX, LocalPolicyStore.MIGRATED_APP_BLOCK_ID_PREFIX)
            .firstOrNull(ruleId::startsWith)
            ?.let { PackageName(ruleId.removePrefix(it)) }
}
