package dev.schrammel.protego_parentis.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import dev.schrammel.protego_parentis.R

/**
 * Everything the My Time dashboard used to show directly: history, privacy, and
 * settings, one tap away instead of filling the first screen.
 *
 * The settings group that used to sit here is now one row leading to
 * [SettingsActivity], which serves both kinds of phone (#78). Listing
 * notifications, permissions and language here while a parent's phone kept its
 * own longer list somewhere else meant the same settings had two homes that
 * had drifted apart.
 */
class HomeMoreActivity : DementorActivity() {

    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        column = contentColumn()
        showColumn(column)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        column.removeAllViews()
        column.addView(screenTitle(getString(R.string.home_more_title)))

        addSection(
            column,
            getString(R.string.home_group_openness),
            listOf(
                row(R.string.home_open_audit, R.string.home_open_audit_detail) {
                    startActivity(AuditActivity.intent(this))
                },
                row(R.string.home_open_privacy, R.string.home_open_privacy_detail) {
                    startActivity(PrivacyActivity.intent(this))
                },
            ),
        )

        addSection(
            column,
            getString(R.string.home_group_settings),
            listOf(
                row(R.string.settings_title, R.string.settings_row_detail) {
                    startActivity(SettingsActivity.intent(this))
                },
                row(R.string.home_edit_rules, R.string.home_edit_rules_detail) {
                    startActivity(Intent(this, RulesActivity::class.java))
                },
            ),
        )
    }

    companion object {
        fun intent(context: Context) = Intent(context, HomeMoreActivity::class.java)
    }
}
