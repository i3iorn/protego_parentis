package dev.schrammel.protego_parentis.ui

import androidx.annotation.StringRes
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.security.DeviceRole

/**
 * What the settings screen offers, as data rather than as a `buildList` inside
 * an Activity (#78).
 *
 * Settings used to live in two places that disagreed: three rows behind More on
 * the managed phone, nine behind Family devices on a parent's, with the same
 * destinations linked from both under different labels. One screen now serves
 * both, and this is the one description of what it holds — a list a test can
 * read, which the version built inline out of views could not be.
 *
 * Only [SettingsDestination] is named here, never how to open it. Which screen
 * a row leads to is this file's business; what launching it does belongs to the
 * Activity, which is the thing that has a Context.
 */
internal enum class SettingsDestination {
    NOTIFICATIONS,
    PERMISSIONS,
    LANGUAGE,
    SECURITY_AND_CONTROL,
    FEATURES,
    ROLE_SWITCH,
}

internal data class SettingsRow(
    @param:StringRes val title: Int,
    @param:StringRes val detail: Int,
    val destination: SettingsDestination,
)

internal data class SettingsSection(
    @param:StringRes val label: Int?,
    val rows: List<SettingsRow>,
)

/**
 * The settings a phone of [role] offers.
 *
 * The first section is unlabelled: the screen title has already said these are
 * settings, and a heading repeating it would only be naming the obvious — the
 * same rule every other screen in this app follows for its first group.
 *
 * Nothing here is gated. Each row's *destination* carries whatever protection
 * it needs, which is where that protection already lived: changing the PIN or
 * the biometric switch means opening [DeviceControlActivity], the feature
 * flags mean [FeatureSettingsActivity], and both are [ParentGatedActivity].
 * That leaves notifications, permissions and language reachable on the managed
 * phone without a PIN, exactly as they were behind More before this screen
 * existed (#43 settled that the child's own preferences stay theirs).
 */
internal fun settingsSections(role: DeviceRole): List<SettingsSection> = listOf(
    SettingsSection(
        label = null,
        rows = listOf(
            SettingsRow(
                R.string.notify_title,
                R.string.notify_row_detail,
                SettingsDestination.NOTIFICATIONS,
            ),
            SettingsRow(
                R.string.perms_title,
                R.string.perms_row_detail,
                SettingsDestination.PERMISSIONS,
            ),
            SettingsRow(
                R.string.home_language,
                R.string.home_language_detail,
                SettingsDestination.LANGUAGE,
            ),
            SettingsRow(
                R.string.rules_row_device,
                R.string.rules_row_device_detail,
                SettingsDestination.SECURITY_AND_CONTROL,
            ),
        ),
    ),
    SettingsSection(
        label = R.string.settings_group_family,
        rows = buildList {
            // Feature flags are family-wide and have always been offered from a
            // parent's phone only. Reaching them from the managed phone would
            // hand whoever holds it a switch that puts away the parts of the app
            // that limit them.
            if (role == DeviceRole.PARENT) {
                add(
                    SettingsRow(
                        R.string.features_title,
                        R.string.features_row_detail,
                        SettingsDestination.FEATURES,
                    ),
                )
                add(
                    SettingsRow(
                        R.string.parent_settings_become_child,
                        R.string.settings_row_role_detail,
                        SettingsDestination.ROLE_SWITCH,
                    ),
                )
            }
        },
    ),
).filter { it.rows.isNotEmpty() }
