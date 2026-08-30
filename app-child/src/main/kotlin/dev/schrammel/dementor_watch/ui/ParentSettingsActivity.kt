package dev.schrammel.dementor_watch.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import dev.schrammel.dementor_watch.R
import dev.schrammel.dementor_watch.audit.Audit
import dev.schrammel.dementor_watch.model.AuditAction
import dev.schrammel.dementor_watch.model.AuditActor
import dev.schrammel.dementor_watch.security.DeviceRole
import dev.schrammel.dementor_watch.security.RoleStore
import dev.schrammel.dementor_watch.sync.ParentPollWorker

/**
 * Settings — reached only from the Family screen, never directly off a dashboard.
 *
 * Used to be a scattering of rows across the parent's dashboard and the on-device
 * rules hub, with the same destination (feature flags, in particular) linked from
 * both. Gathering them here means there is exactly one path to any of them, and
 * that path goes through Family: settings are something you reach *because* you
 * are looking at who is in the family and how their phones are connected, not
 * something a first-time screen throws at you before it has said anything else.
 *
 * Its one real job beyond the links above is the role switch, and that is why the
 * whole screen is PIN-gated while the rest of parent mode is not. The role is the
 * boundary the product rests on: a child who could flip a phone into parent mode
 * could answer their own requests, so changing it costs the PIN wherever it is
 * offered.
 */
class ParentSettingsActivity : ParentGatedActivity() {

    private lateinit var roles: RoleStore
    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        roles = RoleStore(this)
        column = contentColumn()
        showColumn(column)
    }

    override fun onGatedContentVisible() = render()

    override fun onGatedContentHidden() {
        column.removeAllViews()
    }

    private fun render() {
        column.removeAllViews()
        column.addView(screenTitle(getString(R.string.parent_settings_title)))

        addSection(
            column,
            getString(R.string.parent_group_settings),
            listOf(
                row(R.string.parent_row_sync, R.string.parent_row_sync_detail) {
                    startActivity(JoinFamilyActivity.intent(this))
                },
                navigationRow(
                    getString(R.string.home_language, LanguagePicker.currentLabel(this)),
                    getString(R.string.home_language_detail),
                ) { LanguagePicker.show(this) },
                row(R.string.notify_title, R.string.notify_row_detail) {
                    startActivity(NotificationSettingsActivity.intent(this))
                },
                row(R.string.perms_title, R.string.perms_row_detail) {
                    startActivity(PermissionsActivity.intent(this))
                },
                row(R.string.features_title, R.string.features_row_detail) {
                    startActivity(FeatureSettingsActivity.intent(this))
                },
                row(R.string.rules_row_device, R.string.rules_row_device_detail) {
                    startActivity(Intent(this, DeviceControlActivity::class.java))
                },
            ),
        )

        // Each setting on its own surface, with its explanation inside it. Loose
        // buttons under loose paragraphs made three settings look like one
        // unbroken column of text with things stuck in it.
        column.addView(sectionHeader(getString(R.string.parent_settings_pin_header)))
        column.addView(
            card {
                addView(captionText(getString(R.string.parent_settings_pin_body)))
                addView(
                    actionButton().apply {
                        setText(R.string.rules_change_pin)
                        setOnClickListener {
                            startActivity(PinActivity.intent(context, change = true))
                        }
                    },
                )
            },
        )

        column.addView(sectionHeader(getString(R.string.biometric_header)))
        column.addView(card { addView(biometricSetting { render() }) })

        column.addView(sectionHeader(getString(R.string.parent_settings_role_header)))
        column.addView(
            card {
                addView(captionText(getString(R.string.parent_settings_role_body)))
                addView(
                    actionButton().apply {
                        setText(R.string.parent_settings_become_child)
                        setOnClickListener { confirmSwitch() }
                    },
                )
            },
        )
    }

    /**
     * Switching to child mode turns this phone into the managed one. Nothing is
     * deleted, but the app starts collecting usage and enforcing rules here, which
     * is not a thing to do on a mistaken tap.
     */
    private fun confirmSwitch() {
        confirmDialog(
            R.string.parent_settings_become_child,
            R.string.parent_settings_become_child_confirm,
            R.string.parent_settings_become_child,
        ) {
            roles.choose(DeviceRole.CHILD)
            ParentPollWorker.cancel(this)
            Audit.record(
                context = this,
                actor = AuditActor.PARENT,
                action = AuditAction.ROLE_CHANGED,
                before = DeviceRole.PARENT.name,
                after = DeviceRole.CHILD.name,
            )
            startActivity(
                Intent(this, HomeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            finish()
        }
    }

    companion object {
        fun intent(context: Context) = Intent(context, ParentSettingsActivity::class.java)
    }
}
