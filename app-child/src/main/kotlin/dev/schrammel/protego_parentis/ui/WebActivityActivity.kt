package dev.schrammel.protego_parentis.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.CheckBox
import android.widget.LinearLayout
import dev.schrammel.protego_parentis.AppRegistry
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.audit.Audit
import dev.schrammel.protego_parentis.enforcement.EnforcementCoordinator
import dev.schrammel.protego_parentis.model.AuditAction
import dev.schrammel.protego_parentis.model.AuditActor
import dev.schrammel.protego_parentis.policy.PolicyClock
import dev.schrammel.protego_parentis.rules.WebActivityStore
import dev.schrammel.protego_parentis.rules.WebFilterStore
import java.time.Instant

/**
 * Where the browsing went (WF-3).
 *
 * Not PIN-gated, and that is deliberate — this is the most intimate thing the app
 * records, so the person it is about sees it without asking anyone. The same
 * reasoning as the shared history: a record only the parent can read is
 * surveillance, and the same record both can read is an account.
 */
class WebActivityActivity : DementorActivity() {

    private lateinit var store: WebActivityStore
    private lateinit var settings: WebFilterStore
    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AppRegistry.childStores.webActivity
        settings = AppRegistry.childStores.webFilter
        column = contentColumn()
        showColumn(column)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        column.removeAllViews()
        column.addView(screenTitle(getString(R.string.web_activity_title)))
        column.addView(bodyText(getString(R.string.web_activity_intro)))

        column.addView(
            CheckBox(this).apply {
                setText(R.string.web_activity_enable)
                isChecked = settings.countVisits
                // Guards against the programmatic isChecked = true below
                // re-entering this same listener while putting the box back.
                var reverting = false
                setOnCheckedChangeListener { box, wanted ->
                    if (reverting) {
                        reverting = false
                        return@setOnCheckedChangeListener
                    }
                    if (!wanted) {
                        // Unchecking this reads as "pause logging for now," but it
                        // also permanently erases every visit recorded so far —
                        // a bigger, more silent failure mode than the confirmed
                        // actions elsewhere in this app (e.g. revoking a device).
                        // Put the box back until the parent actually confirms.
                        reverting = true
                        box.isChecked = true
                        confirmDialog(
                            R.string.web_activity_clear_title,
                            R.string.web_activity_clear_body,
                            R.string.web_activity_clear_confirm,
                        ) {
                            settings.countVisits = false
                            store.clear()
                            recordLoggingChange(false)
                            reverting = true
                            box.isChecked = false
                        }
                        return@setOnCheckedChangeListener
                    }
                    settings.countVisits = true
                    recordLoggingChange(true)
                }
            },
        )
        column.addView(captionText(getString(R.string.web_activity_enable_hint)))

        // The same policy day the budget resets on, not real midnight — a family
        // with a day-boundary hour set would otherwise see this list clear four
        // hours before the budget it is shown alongside does.
        val policy = AppRegistry.childStores.policy.load()
        val today = PolicyClock.policyDay(Instant.now(), policy.timeZone, policy.dayBoundaryHour)
        val visits = store.visits(today)

        column.addView(sectionHeader(getString(R.string.parent_today_header)))
        if (!settings.countVisits) {
            column.addView(bodyText(getString(R.string.web_activity_off)))
        } else if (visits.isEmpty()) {
            column.addView(bodyText(getString(R.string.web_activity_empty)))
        } else {
            column.addView(
                group(
                    visits.map { visit ->
                        statRow(
                            visit.domain,
                            resources.getQuantityString(
                                R.plurals.web_activity_count,
                                visit.count,
                                visit.count,
                            ),
                        )
                    },
                ),
            )
        }

        val blocked = store.blockedAttempts(today)
        if (blocked > 0) {
            column.addView(
                captionText(
                    resources.getQuantityString(R.plurals.web_activity_blocked, blocked, blocked),
                ),
            )
        }

        // Said plainly, because a list of domains reads like a complete account of
        // an afternoon and is nothing of the kind.
        column.addView(captionText(getString(R.string.web_activity_limits)))

        Background.load(this, { EnforcementCoordinator(this).isDeviceOwner() }) { owner ->
            if (!owner) column.addView(captionText(getString(R.string.rules_device_status_monitor)))
        }
    }

    private fun recordLoggingChange(enabled: Boolean) {
        Audit.record(
            context = this,
            actor = AuditActor.PARENT,
            action = AuditAction.WEB_LOGGING_CHANGED,
            after = Audit.flag(enabled),
        )
        AppRegistry.ruleMutationSink.rulesChanged()
    }

    companion object {
        fun intent(context: Context) = Intent(context, WebActivityActivity::class.java)
    }
}
