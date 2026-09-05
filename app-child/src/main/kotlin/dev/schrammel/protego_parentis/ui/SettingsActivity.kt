package dev.schrammel.protego_parentis.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.security.RoleStore

/**
 * Settings, in one place, on both kinds of phone (#78).
 *
 * There used to be two: three rows behind More on the managed phone, and nine
 * behind Family devices on a parent's, reached "only by way of the family it
 * configures" — a rule that reads well and meant nobody found them. The same
 * destinations were linked from both under different labels, and the parent's
 * copy carried its own PIN and biometric cards duplicating the ones already on
 * [DeviceControlActivity].
 *
 * Not PIN-gated, which is the same footing More was on. Nothing here changes
 * anything by itself — every row opens a screen, and the ones that need the PIN
 * ask for it themselves ([DeviceControlActivity], [FeatureSettingsActivity],
 * [RoleSwitchActivity] are all [ParentGatedActivity]). Gating this screen
 * instead would take the managed phone's own notification and language settings
 * away from the person holding it, which #43 decided against on purpose.
 *
 * What is on the screen comes from [settingsSections]; this class only knows
 * how to open things.
 */
class SettingsActivity : DementorActivity() {

    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        column = contentColumn()
        showColumn(column)
    }

    override fun onResume() {
        super.onResume()
        // Rebuilt rather than built once: the language row shows the language it
        // would change, and coming back from having changed it must not leave the
        // old one named on the row.
        render()
    }

    private fun render() {
        column.removeAllViews()
        column.addView(screenTitle(getString(R.string.settings_title)))

        settingsSections(RoleStore(this).roleOrChild).forEach { section ->
            addSection(
                column,
                section.label?.let(::getString),
                section.rows.map { entry ->
                    navigationRow(titleOf(entry), getString(entry.detail)) { open(entry.destination) }
                },
            )
        }
    }

    /**
     * The language row names the language it is currently set to, so its title
     * is the one row here that cannot come straight from a string resource.
     */
    private fun titleOf(entry: SettingsRow): String = when (entry.destination) {
        SettingsDestination.LANGUAGE ->
            getString(R.string.home_language, LanguagePicker.currentLabel(this))

        else -> getString(entry.title)
    }

    private fun open(destination: SettingsDestination) = when (destination) {
        SettingsDestination.NOTIFICATIONS ->
            startActivity(NotificationSettingsActivity.intent(this))

        SettingsDestination.PERMISSIONS -> startActivity(PermissionsActivity.intent(this))
        SettingsDestination.LANGUAGE -> LanguagePicker.show(this)
        SettingsDestination.SECURITY_AND_CONTROL ->
            startActivity(Intent(this, DeviceControlActivity::class.java))

        SettingsDestination.FEATURES -> startActivity(FeatureSettingsActivity.intent(this))
        SettingsDestination.ROLE_SWITCH -> startActivity(RoleSwitchActivity.intent(this))
    }

    companion object {
        fun intent(context: Context) = Intent(context, SettingsActivity::class.java)
    }
}
