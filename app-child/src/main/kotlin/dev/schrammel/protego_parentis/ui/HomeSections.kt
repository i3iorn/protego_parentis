package dev.schrammel.protego_parentis.ui

import androidx.annotation.StringRes
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.rules.Feature
import dev.schrammel.protego_parentis.security.DeviceRole

/**
 * Where each row on a phone's home screen leads (#78).
 *
 * The two home screens used to be designed separately — different groups, a
 * different order, "Family" naming three different rows depending which screen
 * you read it on. Both now render the same shape: an unlabelled group for
 * whatever this phone was opened to do, then Rules, Family, Openness and
 * Settings, in that order, on both kinds of phone. Only which rows land inside
 * each group differs, and that difference is real — a parent's phone has no
 * usage of its own to show, a managed phone has nobody else's device to check
 * on — not a difference in what the groups are called or where they sit.
 *
 * `HomeDestination` names *where a row goes*, never *how to get there*. Each
 * home Activity maps its own destinations to a `startActivity` call or a local
 * action (locking the phone, asking where it is) in its own `open()` — the same
 * split [SettingsActivity] already uses for the same reason: this file has no
 * `Context`, and shouldn't need one to be read by a test.
 */
internal enum class HomeDestination {
    // The managed phone's own things to do right now.
    REQUESTS,
    WEEK,
    WEB_ACTIVITY,

    // A parent's own things to do right now. LOCK_NOW and LOCATE are local
    // actions, not screens — see each home Activity's own `open()`.
    INBOX,
    LOCK_NOW,
    LOCATE,

    // Shared by both roles.
    CHAT,
    RULES,
    FAMILY_DEVICES,
    THE_OTHER_PHONE,
    AUDIT,
    PRIVACY,
    SETTINGS,
    ;

    /**
     * True for a destination that runs a local action rather than opening a
     * screen — [LOCK_NOW] and [LOCATE]. Those are rendered as a row without a
     * chevron, because a chevron promises somewhere else to go and tapping them
     * does not.
     */
    val isAction: Boolean
        get() = this == LOCK_NOW || this == LOCATE
}

internal data class HomeRow(
    @param:StringRes val title: Int,
    @param:StringRes val detail: Int,
    val destination: HomeDestination,
)

internal data class HomeSection(
    @param:StringRes val label: Int?,
    val rows: List<HomeRow>,
)

/**
 * What [role]'s home screen offers, given which parts of the app [isEnabled]
 * says are switched on.
 *
 * Takes a predicate rather than a concrete [dev.schrammel.protego_parentis.rules.FeatureFlags]
 * because the two roles no longer read the same kind of thing (#91): a managed
 * phone's row set still comes straight from its own local flags, but a
 * parent's now has to ask about a specific managed child's replicated ones —
 * see [ParentHomeActivity]'s call site.
 *
 * [HomeDestination.PRIVACY] is deliberately never offered to a parent: it is
 * titled "what leaves this phone" and reads this device's own web-filter and
 * diagnostic state — on a parent's phone that is a report on the wrong device,
 * not a shorter version of the truth. [HomeDestination.AUDIT] is the opposite
 * case, the *same* shared family record read from either phone, which is why
 * it appears in Openness on both rather than only on one.
 */
internal fun homeSections(role: DeviceRole, isEnabled: (Feature) -> Boolean): List<HomeSection> = when (role) {
    DeviceRole.CHILD -> listOf(
        HomeSection(
            label = null,
            rows = buildList {
                if (isEnabled(Feature.REQUESTS)) {
                    add(HomeRow(R.string.home_open_requests, R.string.home_open_requests_detail, HomeDestination.REQUESTS))
                }
                if (isEnabled(Feature.CHAT)) {
                    add(HomeRow(R.string.chat_title, R.string.chat_row_detail, HomeDestination.CHAT))
                }
                add(HomeRow(R.string.home_open_week, R.string.home_open_week_detail, HomeDestination.WEEK))
                if (isEnabled(Feature.WEB_ACTIVITY)) {
                    add(
                        HomeRow(
                            R.string.rules_row_web_activity,
                            R.string.rules_row_web_activity_detail,
                            HomeDestination.WEB_ACTIVITY,
                        ),
                    )
                }
            },
        ),
        HomeSection(
            label = R.string.home_group_rules,
            rows = listOf(HomeRow(R.string.home_edit_rules, R.string.home_edit_rules_detail, HomeDestination.RULES)),
        ),
        HomeSection(
            label = R.string.home_group_family,
            rows = listOf(
                HomeRow(R.string.rules_row_devices, R.string.rules_row_devices_detail, HomeDestination.FAMILY_DEVICES),
            ),
        ),
        HomeSection(
            label = R.string.home_group_openness,
            rows = listOf(
                HomeRow(R.string.home_open_audit, R.string.home_open_audit_detail, HomeDestination.AUDIT),
                HomeRow(R.string.home_open_privacy, R.string.home_open_privacy_detail, HomeDestination.PRIVACY),
            ),
        ),
        HomeSection(
            label = R.string.home_group_settings,
            rows = listOf(HomeRow(R.string.settings_title, R.string.settings_row_detail, HomeDestination.SETTINGS)),
        ),
    )

    DeviceRole.PARENT -> listOf(
        HomeSection(
            label = null,
            rows = buildList {
                if (isEnabled(Feature.REQUESTS)) {
                    add(HomeRow(R.string.parent_row_inbox, R.string.parent_row_inbox_detail, HomeDestination.INBOX))
                }
                if (isEnabled(Feature.CHAT)) {
                    add(HomeRow(R.string.chat_title, R.string.chat_row_detail, HomeDestination.CHAT))
                }
                add(HomeRow(R.string.parent_row_lock, R.string.parent_row_lock_detail, HomeDestination.LOCK_NOW))
                if (isEnabled(Feature.LOCATION)) {
                    add(HomeRow(R.string.parent_row_locate, R.string.parent_row_locate_detail, HomeDestination.LOCATE))
                }
            },
        ),
        HomeSection(
            label = R.string.home_group_rules,
            rows = listOf(HomeRow(R.string.parent_row_rules, R.string.parent_row_rules_detail, HomeDestination.RULES)),
        ),
        HomeSection(
            label = R.string.home_group_family,
            rows = listOf(
                HomeRow(R.string.parent_row_device, R.string.parent_row_device_detail, HomeDestination.THE_OTHER_PHONE),
                HomeRow(R.string.rules_row_devices, R.string.rules_row_devices_detail, HomeDestination.FAMILY_DEVICES),
            ),
        ),
        HomeSection(
            label = R.string.home_group_openness,
            rows = listOf(HomeRow(R.string.parent_row_audit, R.string.parent_row_audit_detail, HomeDestination.AUDIT)),
        ),
        HomeSection(
            label = R.string.home_group_settings,
            rows = listOf(HomeRow(R.string.settings_title, R.string.settings_row_detail, HomeDestination.SETTINGS)),
        ),
    )
}.filter { it.rows.isNotEmpty() }

/**
 * Whether [feature] should show on a parent's dashboard (#91), given each
 * managed child's replicated `disabledFeatures` set (null for a child this
 * phone has not yet heard a policy from).
 *
 * A pure predicate on purpose — see [ParentHomeActivity.disabledFeaturesByChild]'s
 * own doc comment for why the read that produces [disabledByChild] happens
 * exactly once per screen render rather than once per feature checked here.
 *
 * Hiding a row only once *every* managed child has the feature switched off
 * is the direction that never surprises a parent: it can only ever make a
 * row disappear that no child still uses, never one that some child does. A
 * child this phone has not yet heard a policy from counts as still having it
 * on, matching the "everything on by default" rule
 * [dev.schrammel.protego_parentis.rules.FeatureFlags] already applies locally.
 */
internal fun featureVisible(feature: Feature, disabledByChild: List<Set<String>?>): Boolean {
    if (disabledByChild.isEmpty()) return true
    return disabledByChild.any { disabled -> disabled == null || feature.name !in disabled }
}
