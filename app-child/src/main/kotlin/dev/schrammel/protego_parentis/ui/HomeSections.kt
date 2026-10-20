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
    CONTEXT_RULES,
    FAMILY_DEVICES,
    THE_OTHER_PHONE,
    AUDIT,
    PRIVACY,
    SETTINGS,

    // The controls the "More controls" hub used to hold behind a level of
    // its own. They are destinations like any other now, and a home screen's
    // menu is where a destination belongs.
    CATEGORIES,
    APP_LIMITS,
    APP_CONTROL,
    WEB_FILTER,
    PLACES,
    ARRIVALS,
    NIGHTSCOUT,
    GOAL,
    BONUS,
    REQUEST_INBOX,
    AUTO_APPROVE,
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
            rows = listOf(
                // The child's own window into the rules that apply to them.
                // The editable rules screen is behind the parent's PIN, so
                // without this the child could not see the rules at all -
                // which is what the dashboard's read-only list was for.
                HomeRow(R.string.home_my_rules, R.string.home_my_rules_detail, HomeDestination.CONTEXT_RULES),
            ),
        ),
        HomeSection(
            label = R.string.rules_group_limits,
            rows = buildList {
                if (isEnabled(Feature.CATEGORIES)) {
                    add(
                        HomeRow(
                            R.string.rules_row_categories,
                            R.string.rules_row_categories_detail,
                            HomeDestination.CATEGORIES,
                        ),
                    )
                }
                if (isEnabled(Feature.APP_LIMITS)) {
                    add(
                        HomeRow(
                            R.string.rules_row_app_limits,
                            R.string.rules_row_app_limits_detail,
                            HomeDestination.APP_LIMITS,
                        ),
                    )
                }
                if (isEnabled(Feature.APP_CONTROL)) {
                    add(
                        HomeRow(
                            R.string.rules_row_apps,
                            R.string.rules_row_apps_detail,
                            HomeDestination.APP_CONTROL,
                        ),
                    )
                }
                if (isEnabled(Feature.WEB_FILTER)) {
                    add(
                        HomeRow(
                            R.string.rules_row_web,
                            R.string.rules_row_web_detail,
                            HomeDestination.WEB_FILTER,
                        ),
                    )
                }
            },
        ),
        HomeSection(
            label = R.string.rules_group_day,
            rows = buildList {
                if (isEnabled(Feature.PLACES)) {
                    add(
                        HomeRow(
                            R.string.rules_row_places_alerts,
                            R.string.rules_row_places_alerts_detail,
                            HomeDestination.ARRIVALS,
                        ),
                    )
                }
                if (isEnabled(Feature.CONTEXT_RULES)) {
                    add(
                        HomeRow(
                            R.string.rules_row_nightscout,
                            R.string.rules_row_nightscout_detail,
                            HomeDestination.NIGHTSCOUT,
                        ),
                    )
                }
                if (isEnabled(Feature.GOALS)) {
                    add(
                        HomeRow(
                            R.string.rules_row_goal,
                            R.string.rules_row_goal_detail,
                            HomeDestination.GOAL,
                        ),
                    )
                }
                if (isEnabled(Feature.BONUS)) {
                    add(
                        HomeRow(
                            R.string.rules_row_bonus,
                            R.string.rules_row_bonus_detail,
                            HomeDestination.BONUS,
                        ),
                    )
                }
            },
        ),
        HomeSection(
            label = R.string.rules_group_requests,
            rows = buildList {
                if (isEnabled(Feature.REQUESTS)) {
                    add(
                        HomeRow(
                            R.string.rules_row_requests,
                            R.string.rules_row_requests_detail,
                            HomeDestination.REQUEST_INBOX,
                        ),
                    )
                }
                if (isEnabled(Feature.AUTO_APPROVE)) {
                    add(
                        HomeRow(
                            R.string.rules_row_auto,
                            R.string.rules_row_auto_detail,
                            HomeDestination.AUTO_APPROVE,
                        ),
                    )
                }
            },
        ),        HomeSection(
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
            label = R.string.rules_group_limits,
            rows = buildList {
                if (isEnabled(Feature.CATEGORIES)) {
                    add(
                        HomeRow(
                            R.string.rules_row_categories,
                            R.string.rules_row_categories_detail,
                            HomeDestination.CATEGORIES,
                        ),
                    )
                }
                if (isEnabled(Feature.APP_LIMITS)) {
                    add(
                        HomeRow(
                            R.string.rules_row_app_limits,
                            R.string.rules_row_app_limits_detail,
                            HomeDestination.APP_LIMITS,
                        ),
                    )
                }
                if (isEnabled(Feature.APP_CONTROL)) {
                    add(
                        HomeRow(
                            R.string.rules_row_apps,
                            R.string.rules_row_apps_detail,
                            HomeDestination.APP_CONTROL,
                        ),
                    )
                }
            },
        ),
        HomeSection(
            label = R.string.rules_group_day,
            rows = buildList {
                if (isEnabled(Feature.PLACES)) {
                    add(
                        HomeRow(
                            R.string.rules_row_places,
                            R.string.rules_row_places_detail,
                            HomeDestination.PLACES,
                        ),
                    )
                }
                if (isEnabled(Feature.BONUS)) {
                    add(
                        HomeRow(
                            R.string.rules_row_bonus,
                            R.string.rules_row_bonus_detail,
                            HomeDestination.BONUS,
                        ),
                    )
                }
            },
        ),
        HomeSection(
            label = R.string.rules_group_requests,
            rows = buildList {
                if (isEnabled(Feature.AUTO_APPROVE)) {
                    add(
                        HomeRow(
                            R.string.rules_row_auto,
                            R.string.rules_row_auto_detail,
                            HomeDestination.AUTO_APPROVE,
                        ),
                    )
                }
            },
        ),        HomeSection(
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
 * Whether [destination] stays on the dashboard itself rather than moving into
 * the menu.
 *
 * The child's "ask for more time" and "see my week" are things to do, not
 * places to browse, and a child who wants more time should not have to open a
 * menu to ask for it. Everything else is a destination, and a destination
 * belongs in a menu.
 */
internal fun staysOnDashboard(destination: HomeDestination): Boolean =
    destination == HomeDestination.REQUESTS || destination == HomeDestination.WEEK

/**
 * The menu's sections: [homeSections] without the rows the dashboard carries.
 *
 * The same rows, the same grouping, the same one-line descriptions - only
 * somewhere else.
 */
internal fun homeMenuSections(
    role: DeviceRole,
    isEnabled: (Feature) -> Boolean,
): List<HomeSection> = homeSections(role, isEnabled)
    .map { section -> section.copy(rows = section.rows.filterNot { staysOnDashboard(it.destination) }) }
    .filter { it.rows.isNotEmpty() }

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
