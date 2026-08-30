package dev.schrammel.dementor_watch.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.schrammel.dementor_watch.R
import dev.schrammel.dementor_watch.audit.Audit
import dev.schrammel.dementor_watch.model.AuditAction
import dev.schrammel.dementor_watch.model.AuditActor
import dev.schrammel.dementor_watch.model.Heartbeat
import dev.schrammel.dementor_watch.model.IntegrityProblem
import dev.schrammel.dementor_watch.policy.IntegrityRules
import android.content.ActivityNotFoundException
import androidx.core.net.toUri
import dev.schrammel.dementor_watch.sync.FamilyEventStore
import dev.schrammel.dementor_watch.sync.HeartbeatStore
import dev.schrammel.dementor_watch.sync.NewApp
import dev.schrammel.dementor_watch.sync.NewAppStore
import dev.schrammel.dementor_watch.sync.SyncCoordinator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * How the child's phone is doing, from a parent's phone (TR-2, AC-3).
 *
 * The screen exists because silence is the failure mode nobody notices. A phone
 * that stops enforcing looks exactly like a well-behaved week, and the only way
 * to tell them apart is for the device to be expected to check in.
 *
 * Everything here came out of a heartbeat, so everything here is about the app
 * and the phone — never what was used or when. The child's own dashboard has that,
 * and a parent who wants it can ask to look at it.
 */
class ParentDeviceActivity : ParentRoleActivity() {

    private lateinit var heartbeats: HeartbeatStore
    private lateinit var newApps: NewAppStore
    private lateinit var family: FamilyEventStore
    private lateinit var sync: SyncCoordinator
    private lateinit var column: LinearLayout

    private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern(TIME_PATTERN)

    override fun onParentPhoneReady(savedInstanceState: Bundle?) {
        heartbeats = HeartbeatStore(this)
        newApps = NewAppStore(this)
        family = FamilyEventStore(this)
        sync = SyncCoordinator(this)
        column = contentColumn()
        showColumn(column)
    }

    override fun onParentPhoneResumed() {
        render()
        sync.pollNow {
            runOnUiThread { if (!isFinishing && !isDestroyed) render() }
        }
    }

    private fun render() {
        column.removeAllViews()
        column.addView(screenTitle(getString(R.string.device_title)))

        val now = Instant.now()
        val heartbeat = heartbeats.latest
        val problems = IntegrityRules.problems(
            heartbeat = heartbeat,
            now = now,
            historyRewritten = heartbeats.historySuspect,
        )

        column.addView(statusCard(heartbeat, problems, now))

        if (problems.isNotEmpty()) {
            column.addView(sectionHeader(getString(R.string.device_attention_header)))
            column.addView(group(problems.map(::problemRow)))
        }

        column.addView(sectionHeader(getString(R.string.parent_row_locate)))
        column.addView(whereCard())

        family.transitions().take(TRANSITION_ROWS).takeIf { it.isNotEmpty() }?.let { events ->
            column.addView(sectionHeader(getString(R.string.parent_places_header)))
            column.addView(
                group(
                    events.map { event ->
                        statRow(
                            getString(
                                if (event.arrived) {
                                    R.string.place_arrived_body
                                } else {
                                    R.string.place_left_body
                                },
                                event.place,
                            ),
                            timeFormat.format(event.at.atZone(ZoneId.systemDefault())),
                        )
                    },
                ),
            )
        }

        family.webSummary()?.takeIf { it.domains.isNotEmpty() }?.let { web ->
            column.addView(sectionHeader(getString(R.string.web_activity_title)))
            column.addView(
                group(
                    web.domains.take(DOMAIN_ROWS).map { (domain, count) ->
                        statRow(
                            domain,
                            resources.getQuantityString(
                                R.plurals.web_activity_count,
                                count,
                                count,
                            ),
                        )
                    },
                ),
            )
            column.addView(captionText(getString(R.string.web_activity_limits)))
        }

        val pending = newApps.all()
        if (pending.isNotEmpty()) {
            column.addView(sectionHeader(getString(R.string.device_new_apps_header)))
            column.addView(captionText(getString(R.string.device_new_apps_hint)))
            pending.forEach { column.addView(newAppCard(it)) }
        }
    }

    private fun statusCard(
        heartbeat: Heartbeat?,
        problems: List<IntegrityProblem>,
        now: Instant,
    ): LinearLayout = card {
        addView(
            TextView(this@ParentDeviceActivity).apply {
                text = when {
                    heartbeat == null -> getString(R.string.device_never_heard)
                    problems.any(IntegrityProblem::isUrgent) ->
                        getString(R.string.device_needs_attention)

                    else -> getString(R.string.device_healthy)
                }
                styleAs(R.style.TextAppearance_Dementor_Subtitle)
            },
        )

        if (heartbeat == null) {
            addView(captionText(getString(R.string.device_never_heard_hint)))
            return@card
        }

        addView(
            statRow(
                getString(R.string.device_last_heard),
                IntegrityRules.quietFor(heartbeat, now)
                    ?.let { getString(R.string.device_ago, formatDuration(it)) }
                    ?: getString(R.string.device_clock_off),
            ),
        )
        addView(
            statRow(
                getString(R.string.device_battery),
                if (heartbeat.batteryKnown) {
                    getString(R.string.device_battery_percent, heartbeat.batteryPercent)
                } else {
                    getString(R.string.device_unknown)
                },
            ),
        )
        addView(
            statRow(
                getString(R.string.device_enforcing),
                getString(
                    if (heartbeat.managed) R.string.device_yes else R.string.device_monitor_only,
                ),
            ),
        )
        addView(
            statRow(
                getString(R.string.device_apps_held),
                heartbeat.suspendedCount.toString(),
            ),
        )
        addView(
            statRow(
                getString(R.string.device_rules_version),
                heartbeat.policyVersion.toString(),
            ),
        )
    }

    /**
     * The last position the child's phone sent.
     *
     * One position, with no history behind it. A parent can ask again; they
     * cannot scroll back, because a phone quietly accumulating a movement log
     * somewhere the child cannot see it is the thing this product refuses to be.
     */
    private fun whereCard(): LinearLayout = card {
        val position = family.lastPosition
        if (position == null) {
            addView(
                TextView(this@ParentDeviceActivity).apply {
                    setText(R.string.parent_locate_none)
                    styleAs(R.style.TextAppearance_Dementor_Subtitle)
                },
            )
            addView(captionText(getString(R.string.parent_row_locate_detail)))
            return@card
        }

        addView(
            TextView(this@ParentDeviceActivity).apply {
                text = getString(
                    R.string.parent_locate_at,
                    timeFormat.format(position.at.atZone(ZoneId.systemDefault())),
                )
                styleAs(R.style.TextAppearance_Dementor_Subtitle)
            },
        )
        addView(
            captionText(
                getString(R.string.parent_locate_accuracy, position.accuracyMetres),
            ),
        )
        addView(
            Button(this@ParentDeviceActivity).apply {
                setText(R.string.parent_locate_open)
                setOnClickListener { openInMaps(position.latitude, position.longitude) }
            },
        )
    }

    /**
     * Hands the position to whatever map app is installed.
     *
     * A `geo:` intent rather than a map drawn here: this app has no map library,
     * no tile budget and no business having either, and every phone already has
     * something that does this better.
     */
    private fun openInMaps(latitude: Double, longitude: Double) {
        val uri = "geo:$latitude,$longitude?q=$latitude,$longitude".toUri()
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            .onFailure { error ->
                if (error is ActivityNotFoundException) {
                    toast(R.string.parent_locate_none)
                }
            }
    }

    private fun problemRow(problem: IntegrityProblem): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            setPadding(0, dimen(R.dimen.space_sm), 0, dimen(R.dimen.space_sm))

            addView(
                TextView(context).apply {
                    setText(titleFor(problem))
                    styleAs(R.style.TextAppearance_Dementor_ListItem)
                },
            )
            addView(
                TextView(context).apply {
                    setText(hintFor(problem))
                    styleAs(R.style.TextAppearance_Dementor_Caption)
                },
            )
        }

    /**
     * A new app, with the same two answers the child's phone offers.
     *
     * Answering publishes the decision and drops the row. If the message never
     * arrives the app simply stays unblocked and the child's phone still shows it
     * as unreviewed — which is the honest failure, and better than a parent
     * believing they blocked something they did not.
     */
    private fun newAppCard(app: NewApp): LinearLayout = card {
        addView(
            TextView(this@ParentDeviceActivity).apply {
                text = app.label
                styleAs(R.style.TextAppearance_Dementor_Subtitle)
            },
        )
        addView(captionText(app.packageName))
        addView(
            LinearLayout(this@ParentDeviceActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    topMargin = dimen(R.dimen.space_sm)
                }
                addView(
                    Button(context).apply {
                        setText(R.string.apps_allow)
                        layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                            marginEnd = dimen(R.dimen.space_xs)
                        }
                        setOnClickListener { decide(app, blocked = false) }
                    },
                )
                addView(
                    Button(context).apply {
                        setText(R.string.apps_block)
                        layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                        setOnClickListener { decide(app, blocked = true) }
                    },
                )
            },
        )
    }

    private fun decide(app: NewApp, blocked: Boolean) {
        sync.publishAppDecision(app.packageName, blocked)
        newApps.forget(app.packageName)
        Audit.record(
            context = this,
            actor = AuditActor.PARENT,
            action = AuditAction.APP_BLOCK_CHANGED,
            subject = app.label,
            after = Audit.flag(blocked),
        )
        render()
    }

    private fun titleFor(problem: IntegrityProblem): Int = when (problem) {
        IntegrityProblem.NEVER_HEARD -> R.string.integrity_never_heard
        IntegrityProblem.SILENT ->
            if (heartbeats.stoppedAfterLastHeartbeat()) {
                R.string.integrity_switched_off
            } else {
                R.string.integrity_silent
            }
        IntegrityProblem.USAGE_ACCESS_MISSING -> R.string.integrity_usage_access
        IntegrityProblem.NOT_MANAGED -> R.string.integrity_not_managed
        IntegrityProblem.BATTERY_LOW -> R.string.integrity_battery_low
        IntegrityProblem.HISTORY_REWRITTEN -> R.string.integrity_history
    }

    private fun hintFor(problem: IntegrityProblem): Int = when (problem) {
        IntegrityProblem.NEVER_HEARD -> R.string.integrity_never_heard_hint
        IntegrityProblem.SILENT ->
            if (heartbeats.stoppedAfterLastHeartbeat()) {
                R.string.integrity_switched_off_hint
            } else {
                R.string.integrity_silent_hint
            }
        IntegrityProblem.USAGE_ACCESS_MISSING -> R.string.integrity_usage_access_hint
        IntegrityProblem.NOT_MANAGED -> R.string.integrity_not_managed_hint
        IntegrityProblem.BATTERY_LOW -> R.string.integrity_battery_low_hint
        IntegrityProblem.HISTORY_REWRITTEN -> R.string.integrity_history_hint
    }

    companion object {
        /** Enough to answer "did they get there"; too few to read as a trail. */
        private const val TIME_PATTERN = "HH:mm"

        /** Enough to answer "did they get there"; too few to read as a trail. */
        private const val TRANSITION_ROWS = 6
        private const val DOMAIN_ROWS = 15

        fun intent(context: Context) = Intent(context, ParentDeviceActivity::class.java)
    }
}
