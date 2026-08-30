package dev.schrammel.dementor_watch.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import dev.schrammel.dementor_watch.R

/**
 * Everything the My Time dashboard used to show directly: history, privacy, and
 * settings, one tap away instead of filling the first screen.
 */
class HomeMoreActivity : DementorActivity() {

    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        column = contentColumn()
        showColumn(column)
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
                row(R.string.notify_title, R.string.notify_row_detail) {
                    startActivity(NotificationSettingsActivity.intent(this))
                },
                row(R.string.perms_title, R.string.perms_row_detail) {
                    startActivity(PermissionsActivity.intent(this))
                },
                navigationRow(
                    getString(R.string.home_language, LanguagePicker.currentLabel(this)),
                    getString(R.string.home_language_detail),
                ) { LanguagePicker.show(this) },
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
