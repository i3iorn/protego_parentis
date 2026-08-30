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
 *
 * Built in code rather than from a layout because the groups are now conditional.
 * A fixed layout meant a heading sat there over an empty card when a family had
 * put everything under it away, which reads as a screen that failed to load.
 */
class RulesActivity : ParentGatedActivity() {

    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        column = contentColumn()
        showColumn(column)
    }

    override fun onGatedContentVisible() = render()

    override fun onGatedContentHidden() {
        column.removeAllViews()
    }

    private fun render() {
        column.removeAllViews()
        column.addView(screenTitle(getString(R.string.rules_title)))
        column.addView(statusCard())

        val on = FeatureFlags(this)

        addSection(
            column,
            getString(R.string.rules_group_limits),
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

        addSection(
            column,
            getString(R.string.rules_group_day),
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

        addSection(
            column,
            getString(R.string.rules_group_family),
            buildList {
                if (on.isEnabled(Feature.REQUESTS)) {
                    add(
                        row(R.string.rules_row_requests, R.string.rules_row_requests_detail) {
                            startActivity(RequestInboxActivity.intent(this@RulesActivity))
                        },
                    )
                    if (on.isEnabled(Feature.AUTO_APPROVE)) {
                        add(
                            row(R.string.rules_row_auto, R.string.rules_row_auto_detail) {
                                startActivity(AutoApprovalActivity.intent(this@RulesActivity))
                            },
                        )
                    }
                }
                // The one path to Family from this hub. Its own Settings row is
                // where the sync relay and device control live now, rather than
                // beside it here — a deployment detail and the family roster no
                // longer compete for the same list.
                add(
                    row(R.string.rules_row_devices, R.string.rules_row_devices_detail) {
                        startActivity(FamilyDevicesActivity.intent(this@RulesActivity))
                    },
                )
            },
        )
    }

    /**
     * The device's state, in the one place a parent looks when wondering why
     * nothing is being blocked.
     *
     * Starts on the reassuring reading rather than blank. The lookup is off the
     * main thread, so an empty card would flash on every open, and a status line
     * that blinks looks like a fault whatever it eventually says.
     */
    private fun statusCard(): View {
        val status = TextView(this).apply {
            setText(R.string.rules_device_status_monitor)
            styleAs(R.style.TextAppearance_Dementor_Body)
        }

        Background.load(this, {
            val coordinator = EnforcementCoordinator(this)
            // Read, not recomputed. Asking for a fresh evaluation here would mean
            // opening this screen drove DevicePolicyManager, which is a side
            // effect no one asked a status line to have.
            coordinator.isDeviceOwner() to coordinator.dnsFailedLastPass()
        }) { (owner, dnsFailed) ->
            status.setText(
                when {
                    dnsFailed -> R.string.rules_dns_failed
                    owner -> R.string.rules_device_status_owner
                    else -> R.string.rules_device_status_monitor
                },
            )
        }

        return card { addView(status) }
    }

    override fun onPause() {
        super.onPause()
        // A screen below may have changed the rules; push them through so the
        // effect is live by the time the parent puts the phone down.
        if (isFinishing) EnforcementService.start(this)
    }
}
