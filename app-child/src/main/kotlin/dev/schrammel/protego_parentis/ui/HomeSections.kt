package dev.schrammel.protego_parentis.ui

import androidx.annotation.StringRes
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.rules.Feature
import dev.schrammel.protego_parentis.security.DeviceRole

/**
 * Where each row on a phone's home screen leads.
 *
 * The two home screens used to be designed separately — different groups, a
 * different order, "Family" naming three different rows depending which screen
 * you read it on. Both now render the same shape: an unlabelled group for
 * whatever this phone was opened to do, then the groups below, in that order,
 * on both kinds of phone. Only which rows land inside each group differs, and
 * that difference is real — a parent's phone has no usage of its own to show, a
 * managed phone has nobody else's device to check on — not a difference in what
 * the groups are called or where they sit.
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
 * The groups are named after what the screens in them are *about*, not after
 * a shape: "How much" said nothing about the six screens under it, and the
 * one that did — the limit on each app, the cap on a category, what the
 * phone may and may not do — is the limit, so the group says so.
 *
 * [HomeDestination.PRIVACY] is deliberately never offered to a parent: it is
 * titled "what leaves this phone" and reads this device's own web-filter and
 * diagnostic state — on a parent's phone that is a report on the wrong device,
 * not a shorter version of the truth. [HomeDestination.AUDIT] is the opposite
 * case, the *same* shared family record read from either phone, which is why
 * it appears in the family group on both rather than only on one.
 */
internal fun homeSections(role: DeviceRole, isEnabled: (Feature) -> Boolean): List<HomeSection> = when (role) {
    DeviceRole.CHILD -> listOf(
        // The child's own time: the extra minutes they were given, the goal
        // they are working toward, and the two ways a request for more time
        // goes - asking, and the standing answers that need no asking.
        HomeSection(
            label = R.string.home_group_own_time,
            rows = buildList {
                if (isEnabled(Feature.BONUS)) {
                    add(HomeRow(R.string.rules_row_bonus, R.string.rules_row_bonus_detail, HomeDestination.BONUS))
                }
                if (isEnabled(Feature.GOALS)) {
                    add(HomeRow(R.string.rules_row_goal, R.string.rules_row_goal_detail, HomeDestination.GOAL))
                }
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
        ),
        // What the phone allows: the rules themselves, the groups of apps that
        // share a limit, the limit on one app, the apps that may and may not
        // run, and what the browser may reach.
        HomeSection(
            label = R.string.home_group_own_limits,
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
        // The people: the conversation, whose phones this family trusts, and
        // the shared record of what was decided.
        HomeSection(
            label = R.string.home_group_own_family,
            rows = buildList {
                if (isEnabled(Feature.CHAT)) {
                    add(HomeRow(R.string.chat_title, R.string.chat_row_detail, HomeDestination.CHAT))
                }
                add(
                    HomeRow(
                        R.string.rules_row_devices,
                        R.string.rules_row_devices_detail,
                        HomeDestination.FAMILY_DEVICES,
                    ),
                )
                add(HomeRow(R.string.home_open_audit, R.string.home_open_audit_detail, HomeDestination.AUDIT))
            },
        ),
        // This phone itself: what it notices, what it counts, what it sends,
        // and how it is set up.
        HomeSection(
            label = R.string.home_group_this_phone,
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
                if (isEnabled(Feature.WEB_ACTIVITY)) {
                    add(
                        HomeRow(
                            R.string.rules_row_web_activity,
                            R.string.rules_row_web_activity_detail,
                            HomeDestination.WEB_ACTIVITY,
                        ),
                    )
                }
                add(HomeRow(R.string.home_open_privacy, R.string.home_open_privacy_detail, HomeDestination.PRIVACY))
                add(HomeRow(R.string.settings_title, R.string.settings_row_detail, HomeDestination.SETTINGS))
            },
        ),
    )

    DeviceRole.PARENT -> listOf(
        // What this phone can do to theirs, right now.
        HomeSection(
            label = null,
            rows = buildList {
                add(HomeRow(R.string.parent_row_lock, R.string.parent_row_lock_detail, HomeDestination.LOCK_NOW))
                if (isEnabled(Feature.LOCATION)) {
                    add(HomeRow(R.string.parent_row_locate, R.string.parent_row_locate_detail, HomeDestination.LOCATE))
                }
            },
        ),
        // Their time: the extra minutes they have been given, the requests
        // waiting for an answer, and the standing answers that need no asking.
        // The last two sit side by side: they are the two ways a request for
        // more time goes.
        HomeSection(
            label = R.string.home_group_child_time,
            rows = buildList {
                if (isEnabled(Feature.BONUS)) {
                    add(HomeRow(R.string.rules_row_bonus, R.string.rules_row_bonus_detail, HomeDestination.BONUS))
                }
                if (isEnabled(Feature.REQUESTS)) {
                    add(
                        HomeRow(
                            R.string.parent_row_inbox,
                            R.string.parent_row_inbox_detail,
                            HomeDestination.INBOX,
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
        ),
        // What they are allowed: the rules, the groups of apps that share a
        // limit, the limit on one app, the apps that may and may not run, and
        // the places a rule can refer to.
        HomeSection(
            label = R.string.home_group_child_limits,
            rows = buildList {
                add(HomeRow(R.string.parent_row_rules, R.string.parent_row_rules_detail, HomeDestination.RULES))
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
                if (isEnabled(Feature.PLACES)) {
                    add(
                        HomeRow(
                            R.string.rules_row_places,
                            R.string.rules_row_places_detail,
                            HomeDestination.PLACES,
                        ),
                    )
                }
            },
        ),
        // The people: the conversation, the phone itself, whose phones this
        // family trusts, and the shared record of what was decided.
        HomeSection(
            label = R.string.home_group_child_family,
            rows = buildList {
                if (isEnabled(Feature.CHAT)) {
                    add(HomeRow(R.string.chat_title, R.string.chat_row_detail, HomeDestination.CHAT))
                }
                add(
                    HomeRow(
                        R.string.parent_row_device,
                        R.string.parent_row_device_detail,
                        HomeDestination.THE_OTHER_PHONE,
                    ),
                )
                add(
                    HomeRow(
                        R.string.rules_row_devices,
                        R.string.rules_row_devices_detail,
                        HomeDestination.FAMILY_DEVICES,
                    ),
                )
                add(HomeRow(R.string.parent_row_audit, R.string.parent_row_audit_detail, HomeDestination.AUDIT))
            },
        ),
        // This phone itself.
        HomeSection(
            label = R.string.home_group_this_phone,
            rows = listOf(
                HomeRow(R.string.settings_title, R.string.settings_row_detail, HomeDestination.SETTINGS),
            ),
        ),
    )
}.filter { it.rows.isNotEmpty() }

/**
 * Whether [feature] should show on a parent's dashboard (#91), given each
 * managed child's replicated `disabledFeatures` set (null for a child this
 * phone has not yet heard a policy from).
 *
 * A pure predicate on purpose - see [ParentHomeActivity.disabledFeaturesByChild]'s
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

/**
 * The drawer row's icon: one plain 24dp stroke drawing per destination, tinted
 * from the theme where it is drawn. Deliberately simple shapes — the icon's
 * job is to be found again by shape before it is read by name, not to be
 * looked at on its own.
 */
internal fun iconFor(destination: HomeDestination): Int = when (destination) {
    HomeDestination.WEEK -> R.drawable.ic_dest_week
    HomeDestination.WEB_ACTIVITY -> R.drawable.ic_dest_web_activity
    HomeDestination.INBOX -> R.drawable.ic_dest_inbox
    HomeDestination.LOCK_NOW -> R.drawable.ic_dest_lock
    HomeDestination.LOCATE -> R.drawable.ic_dest_locate
    HomeDestination.CHAT -> R.drawable.ic_dest_chat
    HomeDestination.RULES -> R.drawable.ic_dest_rules
    HomeDestination.CONTEXT_RULES -> R.drawable.ic_dest_context_rules
    HomeDestination.FAMILY_DEVICES -> R.drawable.ic_dest_devices
    HomeDestination.THE_OTHER_PHONE -> R.drawable.ic_dest_phone
    HomeDestination.AUDIT -> R.drawable.ic_dest_audit
    HomeDestination.PRIVACY -> R.drawable.ic_dest_privacy
    HomeDestination.SETTINGS -> R.drawable.ic_dest_settings
    HomeDestination.CATEGORIES -> R.drawable.ic_dest_categories
    HomeDestination.APP_LIMITS -> R.drawable.ic_dest_app_limits
    HomeDestination.APP_CONTROL -> R.drawable.ic_dest_app_control
    HomeDestination.WEB_FILTER -> R.drawable.ic_dest_web_filter
    HomeDestination.PLACES -> R.drawable.ic_dest_places
    HomeDestination.ARRIVALS -> R.drawable.ic_dest_arrivals
    HomeDestination.NIGHTSCOUT -> R.drawable.ic_dest_nightscout
    HomeDestination.GOAL -> R.drawable.ic_dest_goal
    HomeDestination.BONUS -> R.drawable.ic_dest_bonus
    HomeDestination.REQUEST_INBOX -> R.drawable.ic_dest_requests
    HomeDestination.AUTO_APPROVE -> R.drawable.ic_dest_auto_approve
    // A child's own dashboard-only destination; the drawer that asks for an
    // icon never carries it. A shared list glyph is the honest placeholder.
    HomeDestination.REQUESTS -> R.drawable.ic_dest_requests
}
