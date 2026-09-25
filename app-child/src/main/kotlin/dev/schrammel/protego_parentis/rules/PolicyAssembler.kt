package dev.schrammel.protego_parentis.rules

import android.content.Context
import dev.schrammel.protego_parentis.model.AppRule
import dev.schrammel.protego_parentis.model.BrowserPolicy
import dev.schrammel.protego_parentis.model.Category
import dev.schrammel.protego_parentis.model.CategoryId
import dev.schrammel.protego_parentis.model.ChildId
import dev.schrammel.protego_parentis.model.InstallPolicy
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.Policy
import dev.schrammel.protego_parentis.model.WeeklyBudget
import java.time.ZoneId
import kotlin.time.Duration.Companion.minutes

/**
 * Builds the domain [Policy] from the stores behind [LocalPolicyStore].
 *
 * Separates assembly (precedence between the per-app lists, defaults for an
 * absent store) from storage. It is not pure-JVM: the sub-stores are
 * SharedPreferences-backed and [SafetyAllowlist] needs a [Context], so tests
 * of it still run under Robolectric.
 */
internal object PolicyAssembler {

    /**
     * As [LocalPolicyStore.load] — reads the sub-stores through the given
     * references rather than constructing its own.
     */
    fun assemble(
        appContext: Context,
        subStores: PolicySubStores,
        version: Long,
        timeZone: ZoneId,
        dayBoundaryHour: Int,
        childId: String,
        budgetMinutes: Int,
        categoryList: List<StoredCategory>,
        networkBlocked: Set<String>,
    ): Policy {
        val appLimits = subStores.policyStore?.appLimits ?: emptyMap()
        val exemptPackages = subStores.policyStore?.exemptPackages ?: emptySet()
        val blockedPackages = subStores.policyStore?.blockedPackages ?: emptySet()
        val hiddenPackages = subStores.policyStore?.hiddenPackages ?: emptySet()

        return Policy(
            version = version,
            childId = ChildId(childId),
            timeZone = timeZone,
            deviceBudget = budgetOf(budgetMinutes),
            appRules = buildAppRules(appLimits, exemptPackages, blockedPackages, hiddenPackages),
            categories = categoryList.map(::toDomain),
            schedules = subStores.scheduleStore?.active() ?: emptyList(),
            contextRules = subStores.contextRuleStore?.rules ?: emptyList(),
            safetyAllowlist = SafetyAllowlist.of(appContext),
            dnsFilter = subStores.webFilterStore?.filter(),
            browserPolicy = subStores.webFilterStore?.browserPolicy ?: BrowserPolicy.OFF,
            networkBlockedPackages = networkBlocked.map(::PackageName).toSet(),
            installPolicy = subStores.policyStore?.installPolicy ?: InstallPolicy.NOTIFY,
            installWindowUntil = subStores.policyStore?.installWindowUntil,
            tamperProtection = subStores.policyStore?.tamperProtection ?: true,
            dayBoundaryHour = dayBoundaryHour,
        )
    }

    private fun buildAppRules(
        limits: Map<String, Int>,
        exempt: Set<String>,
        blocked: Set<String>,
        hidden: Set<String>,
    ): Map<PackageName, AppRule> = buildMap {
        // Per-app limits first, then exemptions on top: an exempt app ignores
        // every limit, so being on both lists must resolve to exempt.
        limits.forEach { (pkg, mins) -> put(PackageName(pkg), AppRule.Budgeted(budgetOf(mins))) }
        exempt.forEach { put(PackageName(it), AppRule.AlwaysAllowed) }
        // Last, so an explicit block wins over an exemption. "Ignores limits"
        // and "is not on this phone" can both be set from different screens,
        // and the more specific of the two has to be the one that survives.
        blocked.forEach { put(PackageName(it), AppRule.Blocked(hidden = false)) }
        hidden.forEach { put(PackageName(it), AppRule.Blocked(hidden = true)) }
    }

    private fun toDomain(stored: StoredCategory) = Category(
        id = CategoryId(stored.id),
        name = stored.name,
        budget = budgetOf(stored.limitMinutes),
        packages = stored.packages.map(::PackageName).toSet(),
        subcategories = stored.subcategories.map(::CategoryId).toSet(),
    )

    private fun budgetOf(minutes: Int): WeeklyBudget =
        if (minutes <= 0) WeeklyBudget.UNLIMITED else WeeklyBudget.flat(minutes.minutes)

    /**
     * The sub-stores this assembler reads from — passed in so this object
     * never constructs its own, and tests can inject fakes.
     */
    data class PolicySubStores(
        val policyStore: LocalPolicyStore?,
        val scheduleStore: ScheduleStore?,
        val contextRuleStore: ContextRuleStore?,
        val webFilterStore: WebFilterStore?,
    )
}
