package dev.schrammel.protego_parentis.ui

import androidx.annotation.StringRes
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.rules.Feature
import dev.schrammel.protego_parentis.rules.FeatureFlags
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
 * What [role]'s home screen offers, given which parts of the app [flags] has
 * switched on.
 *
 * [HomeDestination.PRIVACY] is deliberately never offered to a parent: it is
 * titled "what leaves this phone" and reads this device's own web-filter and
 * diagnostic state — on a parent's phone that is a report on the wrong device,
 * not a shorter version of the truth. [HomeDestination.AUDIT] is the opposite
 * case, the *same* shared family record read from either phone, which is why
 * it appears in Openness on both rather than only on one.
 */
internal fun homeSections(role: DeviceRole, flags: FeatureFlags): List<HomeSection> = when (role) {
    DeviceRole.CHILD -> listOf(
        HomeSection(
            label = null,
            rows = buildList {
                if (flags.isEnabled(Feature.REQUESTS)) {
                    add(HomeRow(R.string.home_open_requests, R.string.home_open_requests_detail, HomeDestination.REQUESTS))
                }
                if (flags.isEnabled(Feature.CHAT)) {
                    add(HomeRow(R.string.chat_title, R.string.chat_row_detail, HomeDestination.CHAT))
                }
                add(HomeRow(R.string.home_open_week, R.string.home_open_week_detail, HomeDestination.WEEK))
                if (flags.isEnabled(Feature.WEB_ACTIVITY)) {
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
                if (flags.isEnabled(Feature.REQUESTS)) {
                    add(HomeRow(R.string.parent_row_inbox, R.string.parent_row_inbox_detail, HomeDestination.INBOX))
                }
                if (flags.isEnabled(Feature.CHAT)) {
                    add(HomeRow(R.string.chat_title, R.string.chat_row_detail, HomeDestination.CHAT))
                }
                add(HomeRow(R.string.parent_row_lock, R.string.parent_row_lock_detail, HomeDestination.LOCK_NOW))
                if (flags.isEnabled(Feature.LOCATION)) {
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
