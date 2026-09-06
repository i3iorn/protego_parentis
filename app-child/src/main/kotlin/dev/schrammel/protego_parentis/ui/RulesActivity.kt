package dev.schrammel.protego_parentis.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.enforcement.EnforcementCoordinator
import dev.schrammel.protego_parentis.enforcement.EnforcementService
import dev.schrammel.protego_parentis.rules.Feature
import dev.schrammel.protego_parentis.sync.DeviceId
import dev.schrammel.protego_parentis.sync.RosterStore
import dev.schrammel.protego_parentis.ui.rules.policyRuleStore

/**
 * The hub, on whichever kind of phone edits [target]'s rules (#43).
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
 *
 * Which features are switched on comes from [target]'s own replicated
 * `disabledFeatures`, not this device's local [dev.schrammel.protego_parentis.rules.FeatureFlags]
 * — a parent's own phone has no features to speak of, and reading its flags
 * here would answer a question about the wrong device entirely.
 *
 * The daily limit and bonus time route to a local screen ([LimitsActivity],
 * [BonusTimeActivity]) when [target] is this device's own, and to the
 * parent-side send-a-message screen ([ParentLimitActivity], [ParentBonusActivity])
 * otherwise — the same local/remote split #43 already established for every
 * other rule store (#78). Places gets the same split for the same reason
 * (#86): the arrival-announcement toggle only means anything read on the
 * device doing the arriving, but the saved places themselves are exactly the
 * list Contextual rules already edits remotely, so a remote target gets a
 * direct door to [PlacesActivity] rather than losing them along with the
 * toggle. Rows that genuinely cannot work remotely at all — the web filter,
 * web activity, the goal, and requests — still only appear when [target] is
 * this device's own; [render] adds a caption saying so rather than omitting
 * them silently.
 */
class RulesActivity : ParentGatedActivity() {

    private lateinit var target: RuleTarget
    private lateinit var column: LinearLayout

    override fun supportsReadOnly() = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        column = contentColumn()
        showColumn(column)
    }

    override fun onGatedContentVisible() {
        val resolved = RuleTargets.resolve(this) ?: return finish()
        target = resolved
        if (!requireStillInFamily(target)) return
        render()
    }

    override fun onGatedContentHidden() {
        column.removeAllViews()
    }

    private fun render() {
        column.removeAllViews()
        column.addView(screenTitle(getString(R.string.rules_title)))
        // A parent with two managed phones has to be able to tell at a glance
        // which one this screen governs (#43's own done-when list) — this is
        // the one place every path into these screens passes through.
        if (!target.isOwnDevice) {
            val name = RosterStore(this).children().firstOrNull { it.id == target.childId }?.name
            if (name != null) column.addView(captionText(getString(R.string.rules_editing_whose, name)))
            column.addView(captionText(getString(R.string.rules_remote_omissions)))
        }
        if (target.isOwnDevice) column.addView(statusCard())
        if (!canEdit) column.addView(readOnlyBanner { requestUnlock { onGatedContentVisible() } })

        val disabled = policyRuleStore(this, target).read()?.disabledFeatures.orEmpty()
        fun enabled(feature: Feature) = feature.name !in disabled

        addSection(
            column,
            getString(R.string.rules_group_limits),
            buildList {
                // The daily limit is the reason the app exists; it has no switch,
                // and no offline case either — a parent editing it remotely reaches
                // the same send-a-message screen the old top-level "Limit" row did.
                add(
                    row(R.string.rules_row_limits, R.string.rules_row_limits_detail) {
                        if (target.isOwnDevice) {
                            startActivity(Intent(this@RulesActivity, LimitsActivity::class.java))
                        } else {
                            startActivity(ParentLimitActivity.intent(this@RulesActivity))
                        }
                    },
                )
                if (enabled(Feature.CATEGORIES)) {
                    add(
                        row(R.string.rules_row_categories, R.string.rules_row_categories_detail) {
                            startActivity(RuleTargets.putExtra(Intent(this@RulesActivity, CategoriesActivity::class.java), target.childId))
                        },
                    )
                }
                if (enabled(Feature.APP_LIMITS)) {
                    add(
                        row(R.string.rules_row_app_limits, R.string.rules_row_app_limits_detail) {
                            startActivity(AppLimitsActivity.intent(this@RulesActivity, target.childId))
                        },
                    )
                }
                if (enabled(Feature.APP_CONTROL)) {
                    add(
                        row(R.string.rules_row_apps, R.string.rules_row_apps_detail) {
                            startActivity(AppControlActivity.intent(this@RulesActivity, target.childId))
                        },
                    )
                }
                if (target.isOwnDevice && enabled(Feature.WEB_FILTER)) {
                    add(
                        row(R.string.rules_row_web, R.string.rules_row_web_detail) {
                            startActivity(WebFilterActivity.intent(this@RulesActivity))
                        },
                    )
                }
                if (target.isOwnDevice && enabled(Feature.WEB_ACTIVITY)) {
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
                if (enabled(Feature.SCHEDULES)) {
                    add(
                        row(R.string.rules_row_schedules, R.string.rules_row_schedules_detail) {
                            startActivity(SchedulesActivity.intent(this@RulesActivity, target.childId))
                        },
                    )
                }
                if (enabled(Feature.CONTEXT_RULES)) {
                    add(
                        row(R.string.rules_row_context, R.string.rules_row_context_detail) {
                            startActivity(ContextRulesActivity.intent(this@RulesActivity, target.childId))
                        },
                    )
                }
                if (enabled(Feature.PLACES)) {
                    if (target.isOwnDevice) {
                        // Arrival announcements are inherently this phone's own —
                        // they describe what *this* device tells a parent about
                        // itself, which is meaningless read from someone else's.
                        // The saved places it manages are the same list Contextual
                        // rules uses, so a remote target gets a direct door to
                        // those below rather than losing them entirely (#86).
                        add(
                            row(
                                R.string.rules_row_places_alerts,
                                R.string.rules_row_places_alerts_detail,
                            ) { startActivity(PlaceAlertsActivity.intent(this@RulesActivity, target.childId)) },
                        )
                    } else {
                        add(
                            row(R.string.rules_row_places, R.string.rules_row_places_detail) {
                                startActivity(PlacesActivity.intent(this@RulesActivity, target.childId))
                            },
                        )
                    }
                }
                if (target.isOwnDevice && enabled(Feature.GOALS)) {
                    add(
                        row(R.string.rules_row_goal, R.string.rules_row_goal_detail) {
                            startActivity(Intent(this@RulesActivity, GoalActivity::class.java))
                        },
                    )
                }
                if (enabled(Feature.BONUS)) {
                    add(
                        row(R.string.rules_row_bonus, R.string.rules_row_bonus_detail) {
                            if (target.isOwnDevice) {
                                startActivity(BonusTimeActivity.intent(this@RulesActivity))
                            } else {
                                startActivity(ParentBonusActivity.intent(this@RulesActivity))
                            }
                        },
                    )
                }
            },
        )

        // Answering a request only makes sense standing at the phone it came
        // from, so that row stays own-device-only. Family devices used to sit
        // in this section too, but that made "Family" mean two different
        // things app-wide (#78) — it is reachable top-level now, alongside
        // every other family-wide screen.
        //
        // Deciding when a standing yes should apply (#89) is a rule about the
        // child's phone rather than an action on it, so unlike the row above
        // it is not gated to the own device — a parent editing a remote
        // child's rules can set this exactly as they can any other rule.
        if (enabled(Feature.REQUESTS)) {
            val rows = buildList {
                if (target.isOwnDevice) {
                    add(
                        row(R.string.rules_row_requests, R.string.rules_row_requests_detail) {
                            startActivity(RequestInboxActivity.intent(this@RulesActivity))
                        },
                    )
                }
                if (enabled(Feature.AUTO_APPROVE)) {
                    add(
                        row(R.string.rules_row_auto, R.string.rules_row_auto_detail) {
                            startActivity(AutoApprovalActivity.intent(this@RulesActivity, target.childId))
                        },
                    )
                }
            }
            if (rows.isNotEmpty()) addSection(column, getString(R.string.rules_group_requests), rows)
        }
    }

    /**
     * The device's state, in the one place a parent looks when wondering why
     * nothing is being blocked. Only meaningful for this device's own
     * enforcement, so it only ever appears when [target] is this device.
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
        // effect is live by the time the parent puts the phone down — only
        // meaningful for this device's own enforcement.
        if (isFinishing && ::target.isInitialized && target.isOwnDevice) EnforcementService.start(this)
    }

    companion object {
        fun intent(context: android.content.Context, childId: DeviceId? = null) =
            RuleTargets.putExtra(Intent(context, RulesActivity::class.java), childId)
    }
}
