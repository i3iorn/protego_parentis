package dev.schrammel.protego_parentis.rules

import android.content.Context

/**
 * The process-wide store instances, owned once here instead of rebuilt at
 * every construction site.
 *
 * The stores were constructed per call — `LocalPolicyStore` at seventeen
 * main-source sites, `ContextRuleStore` at about a dozen — and each
 * construction re-opened SharedPreferences and rebuilt its sibling stores
 * (LocalPolicyStore alone builds five more). The pattern existed because there
 * was no composition point; [DecodeCache] existed as a workaround for it, and
 * the per-instance `synchronized(prefs)` / [LocalPolicyStore.atomically]
 * transaction state only worked because the framework happens to share one
 * SharedPreferences impl per file name. Shared instances make all of that
 * sound by construction instead of by coincidence.
 *
 * One instance per store is safe: every store already tolerates concurrent
 * use (they are synchronized, and the enforcement worker and UI thread have
 * always shared the same preference files), and every write path treats the
 * stored value as the source of truth rather than instance state.
 *
 * Tests construct stores directly with their own context; production callers
 * go through [AppRegistry.childStores] (or `AppRegistry.childStores.policy`
 * and friends) where they used to write `LocalPolicyStore(context)`.
 */
class ChildStores(context: Context) {

    private val appContext = context.applicationContext

    val policy by lazy { LocalPolicyStore(appContext) }
    val contextRules by lazy { ContextRuleStore(appContext) }
    val schedules by lazy { ScheduleStore(appContext) }
    val goals by lazy { GoalStore(appContext) }
    val grants by lazy { GrantStore(appContext) }
    val requests by lazy { RequestStore(appContext) }
    val appReview by lazy { AppReviewStore(appContext) }
    val autoApprovals by lazy { AutoApprovalStore(appContext) }
    val sos by lazy { SosStore(appContext) }
    val webFilter by lazy { WebFilterStore(appContext) }
    val webActivity by lazy { WebActivityStore(appContext) }
    val expiredRequests by lazy { ExpiredRequestLog(appContext) }
    val featureFlags by lazy { FeatureFlags(appContext) }
}
