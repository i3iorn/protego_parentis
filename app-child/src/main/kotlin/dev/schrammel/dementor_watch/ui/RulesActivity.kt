package dev.schrammel.dementor_watch.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import dev.schrammel.dementor_watch.R
import dev.schrammel.dementor_watch.enforcement.EnforcementCoordinator
import dev.schrammel.dementor_watch.enforcement.EnforcementService
import dev.schrammel.dementor_watch.rules.Feature
import dev.schrammel.dementor_watch.rules.FeatureFlags

/**
 * The parent's hub.
 *
 * This screen used to hold every setting in the app in one scroll, which had grown
 * past the point where anything could be found. Each group now lives on its own
 * screen and this lists them, so the shape of the settings is visible rather than
 * implied by how far down you have scrolled.
 *
 * Grouped by the question being answered — how much, how the day is shaped, who
 * else is involved, control of the device — rather than by which part of the code
 * happens to implement it.
 */
class RulesActivity : ParentGatedActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rules)
    }

    override fun onGatedContentVisible() {
        findViewById<View>(R.id.rules_content).visibility = View.VISIBLE
        buildGroups()
        showDeviceStatus()
    }

    override fun onGatedContentHidden() {
        findViewById<View>(R.id.rules_content).visibility = View.GONE
    }

    private fun buildGroups() {
        val on = FeatureFlags(this)

        fill(
            R.id.group_limits,
            buildList {
                // The daily limit is the reason the app exists; it has no switch.
                add(
                    row(R.string.rules_row_limits, R.string.rules_row_limits_detail) {
                        startActivity(Intent(this@RulesActivity, LimitsActivity::class.java))
                    },
                )
                if (on.isEnabled(Feature.CATEGORIES)) {
                    add(
                        row(R.string.rules_row_categories, R.string.rules_row_categories_detail) {
                            startActivity(Intent(this@RulesActivity, CategoriesActivity::class.java))
                        },
                    )
                }
                if (on.isEnabled(Feature.APP_LIMITS)) {
                    add(
                        row(R.string.rules_row_app_limits, R.string.rules_row_app_limits_detail) {
                            startActivity(AppLimitsActivity.intent(this@RulesActivity))
                        },
                    )
                }
                if (on.isEnabled(Feature.APP_CONTROL)) {
                    add(
                        row(R.string.rules_row_apps, R.string.rules_row_apps_detail) {
                            startActivity(AppControlActivity.intent(this@RulesActivity))
                        },
                    )
                }
                if (on.isEnabled(Feature.WEB_FILTER)) {
                    add(
                        row(R.string.rules_row_web, R.string.rules_row_web_detail) {
                            startActivity(WebFilterActivity.intent(this@RulesActivity))
                        },
                    )
                }
                if (on.isEnabled(Feature.WEB_ACTIVITY)) {
                    add(
                        row(
                            R.string.rules_row_web_activity,
                            R.string.rules_row_web_activity_detail,
                        ) { startActivity(WebActivityActivity.intent(this@RulesActivity)) },
                    )
                }
            },
        )

        fill(
            R.id.group_day,
            buildList {
                if (on.isEnabled(Feature.SCHEDULES)) {
                    add(
                        row(R.string.rules_row_schedules, R.string.rules_row_schedules_detail) {
                            startActivity(SchedulesActivity.intent(this@RulesActivity))
                        },
                    )
                }
                if (on.isEnabled(Feature.CONTEXT_RULES)) {
                    add(
                        row(R.string.rules_row_context, R.string.rules_row_context_detail) {
                            startActivity(ContextRulesActivity.intent(this@RulesActivity))
                        },
                    )
                }
                if (on.isEnabled(Feature.PLACES)) {
                    add(
                        row(
                            R.string.rules_row_places_alerts,
                            R.string.rules_row_places_alerts_detail,
                        ) { startActivity(PlaceAlertsActivity.intent(this@RulesActivity)) },
                    )
                }
                if (on.isEnabled(Feature.GOALS)) {
                    add(
                        row(R.string.rules_row_goal, R.string.rules_row_goal_detail) {
                            startActivity(Intent(this@RulesActivity, GoalActivity::class.java))
                        },
                    )
                }
                if (on.isEnabled(Feature.BONUS)) {
                    add(
                        row(R.string.rules_row_bonus, R.string.rules_row_bonus_detail) {
                            startActivity(BonusTimeActivity.intent(this@RulesActivity))
                        },
                    )
                }
            },
        )

        fill(
            R.id.group_family,
            buildList {
                if (on.isEnabled(Feature.REQUESTS)) {
                    add(
                        row(R.string.rules_row_requests, R.string.rules_row_requests_detail) {
                            startActivity(RequestInboxActivity.intent(this@RulesActivity))
                        },
                    )
                }
                if (on.isEnabled(Feature.REQUESTS) && on.isEnabled(Feature.AUTO_APPROVE)) {
                    add(
                        row(R.string.rules_row_auto, R.string.rules_row_auto_detail) {
                            startActivity(AutoApprovalActivity.intent(this@RulesActivity))
                        },
                    )
                }
                add(
                    row(R.string.rules_row_sync, R.string.rules_row_sync_detail) {
                        startActivity(JoinFamilyActivity.intent(this@RulesActivity))
                    },
                )
                add(
                    row(R.string.rules_row_devices, R.string.rules_row_devices_detail) {
                        startActivity(FamilyDevicesActivity.intent(this@RulesActivity))
                    },
                )
                add(
                    row(R.string.backend_title, R.string.backend_row_detail) {
                        startActivity(SyncBackendActivity.intent(this@RulesActivity))
                    },
                )
            },
        )

        fill(
            R.id.group_device,
            listOf(
                row(R.string.rules_row_device, R.string.rules_row_device_detail) {
                    startActivity(Intent(this, DeviceControlActivity::class.java))
                },
                row(R.string.features_title, R.string.features_row_detail) {
                    startActivity(FeatureSettingsActivity.intent(this))
                },
                row(R.string.notify_title, R.string.notify_row_detail) {
                    startActivity(NotificationSettingsActivity.intent(this))
                },
            ),
        )
    }

    /**
     * The device's state, in the one place a parent looks when wondering why
     * nothing is being blocked.
     */
    private fun showDeviceStatus() {
        Background.load(this, {
            val coordinator = EnforcementCoordinator(this)
            // Read, not recomputed. Asking for a fresh evaluation here would mean
            // opening this screen drove DevicePolicyManager, which is a side
            // effect no one asked a status line to have.
            coordinator.isDeviceOwner() to coordinator.dnsFailedLastPass()
        }) { (owner, dnsFailed) ->
            val status = findViewById<TextView>(R.id.device_status)
            when {
                dnsFailed -> status.setText(R.string.rules_dns_failed)
                owner -> status.setText(R.string.rules_device_status_owner)
                else -> status.setText(R.string.rules_device_status_monitor)
            }
        }
    }

    private fun row(titleRes: Int, detailRes: Int, onClick: () -> Unit) =
        navigationRow(getString(titleRes), getString(detailRes), onClick)

    private fun fill(containerId: Int, rows: List<View>) {
        val container = findViewById<LinearLayout>(containerId)
        container.removeAllViews()
        container.addView(group(rows))
    }

    override fun onPause() {
        super.onPause()
        // A screen below may have changed the rules; push them through so the
        // effect is live by the time the parent puts the phone down.
        if (isFinishing) EnforcementService.start(this)
    }
}
