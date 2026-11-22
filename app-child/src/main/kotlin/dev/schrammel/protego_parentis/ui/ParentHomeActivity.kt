package dev.schrammel.protego_parentis.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.audit.ActivitySeenTracker
import dev.schrammel.protego_parentis.audit.Audit
import dev.schrammel.protego_parentis.model.AuditAction
import dev.schrammel.protego_parentis.model.AuditActor
import dev.schrammel.protego_parentis.model.BudgetLevel
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.sync.DailySummary
import dev.schrammel.protego_parentis.sync.DailySummaryStore
import dev.schrammel.protego_parentis.sync.InboxStore
import dev.schrammel.protego_parentis.sync.ParentPollWorker
import dev.schrammel.protego_parentis.sync.PolicyReplication
import dev.schrammel.protego_parentis.sync.RealtimeSyncService
import dev.schrammel.protego_parentis.sync.RosterStore
import dev.schrammel.protego_parentis.sync.SyncCoordinator
import dev.schrammel.protego_parentis.sync.SyncGraph
import java.time.Instant

/**
 * The dashboard on a parent's own phone.
 *
 * Deliberately thin. This phone holds no usage data, no rules and no enforcement
 * state — everything it knows arrived encrypted over the family topic. So the
 * screen answers one question first, which is the only one a parent opens the app
 * for: is anybody waiting on me?
 *
 * Not PIN-gated, unlike the rules screens on the child's device. This is the
 * parent's own phone, already behind its own lock screen, and a second PIN in
 * front of "approve 15 minutes" is friction with nothing on the other side of it.
 * Switching the phone back to child mode *is* gated — see [RoleSwitchActivity].
 */
class ParentHomeActivity : ParentRoleActivity() {

    /**
     * The dashboard is a root: there is nothing behind it to go back to, so the
     * back affordance its sub-screens carry would only offer to leave the app.
     */
    override val showsBackButton: Boolean = false

    private lateinit var sync: SyncCoordinator
    private lateinit var inbox: InboxStore
    private lateinit var summaries: DailySummaryStore
    private lateinit var column: LinearLayout
    private lateinit var menuButton: android.widget.TextView

    /** The managed children's disabled features, read once per render. */
    private var disabledByChild: List<Set<String>?> = emptyList()
    private lateinit var refresh: SwipeRefreshLayout

    /** Null until this phone has successfully reached the topic in this session. */
    private var lastCheckedAt: Instant? = null


    override fun onParentPhoneReady(savedInstanceState: Bundle?) {
        sync = SyncGraph.get(this)
        inbox = InboxStore(this)
        summaries = DailySummaryStore(this)
        // Idempotent, and re-asserted here so a phone that was granted the family
        // connection after choosing its role still starts listening.
        ParentPollWorker.schedule(this)
        // The realtime stream ensures messages land as soon as they are published
        // rather than waiting for the next poll. Idempotent if already running.
        RealtimeSyncService.start(this)

        column = contentColumn()
        refresh = showRefreshableColumn(column)
        refresh.setOnRefreshListener { pollQuietly() }
    }

    override fun onParentPhoneResumed() {
        render()
        // Opening the app is the moment a parent expects to be up to date, so a
        // poll happens here rather than only on a timer.
        pollQuietly()
    }

    private fun render() {
        column.removeAllViews()

        val configured = sync.settings().isUsable
        val pending = inbox.pending().size
        // A quiet inbox and a quiet connection look identical here unless this is
        // checked separately — "nothing waiting" is also what a family whose sync
        // broke a week ago sees, forever, until someone happens to notice the
        // small "last checked" caption underneath a headline that keeps saying
        // everything is fine.
        val stale = configured && pending == 0 && isCheckInStale()

        // The menu carries the count of what is waiting: a menu hides what is in
        // it, and a badge is what says it is not empty. A parent whose child is
        // asking for more time should not have to open a menu to find out.
        menuButton = menuButton(waiting = pending) { openMenu() }
        column.addView(
            screenTitle(
                getString(R.string.parent_title),
                listOf(menuButton),
            ),
        )

        column.addView(
            card {
                addView(
                    TextView(this@ParentHomeActivity).apply {
                        text = when {
                            !configured -> getString(R.string.parent_not_connected)
                            stale -> getString(R.string.parent_stale_checkin)
                            pending == 0 -> getString(R.string.parent_nothing_waiting)
                            else -> resources.getQuantityString(
                                R.plurals.parent_waiting,
                                pending,
                                pending,
                            )
                        }
                        styleAs(R.style.TextAppearance_Dementor_Subtitle)
                        if (stale) setTextColor(ContextCompat.getColor(context, R.color.state_warning))
                    },
                )
                addView(
                    TextView(this@ParentHomeActivity).apply {
                        text = statusLine(configured)
                        styleAs(R.style.TextAppearance_Dementor_Caption)
                    },
                )
            },
        )

        // A placeholder now, filled in when the answer arrives. Reading the
        // roster touches preference files, and the alternative — holding the
        // whole screen back until it returns — would make the common case, a
        // family that is fine, wait for news that there is nothing to report.
        val banner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(banner)
        Background.load(this, { FamilyStatus.read(this) to RuleVersionWarning.read(this) }) { (snapshot, versions) ->
            ruleVersionBanner(versions) { startActivity(FamilyDevicesActivity.intent(this)) }
                ?.let(banner::addView)
            familyBanner(snapshot) { startActivity(FamilyDevicesActivity.intent(this)) }
                ?.let(banner::addView)
        }

        val activityBannerHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(activityBannerHolder)
        Background.load(this, {
            val entries = Audit.entries(this)
            ActivitySeenTracker(this).unseen(entries).size
        }) { count ->
            activityBanner(count) { startActivity(AuditActivity.intent(this)) }
                ?.let(activityBannerHolder::addView)
        }

        column.addView(sectionHeader(getString(R.string.parent_today_header)))
        column.addView(todayCard())

        // Grouped the same way [HomeActivity] is now (#78): whatever this phone
        // was opened to do, unlabelled; then Rules; Family; Openness; Settings.
        // "Settings" being back on the dashboard rather than one tap inside
        // Family is the same fix as the managed phone's — reachable "only by
        // way of the family it configures" read well and meant nobody found it.
        val disabledByChild = disabledFeaturesByChild()
        this.disabledByChild = disabledByChild
    }

    /**
     * Where this screen leads, when the reader is in the menu.
     *
     * The same destinations the dashboard used to lay out inline, and the same
     * routing: [HomeDestination] names where a row goes, never how to get
     * there, and each home Activity maps its own destinations in its own
     * [open].
     */
    private fun openMenu() {
        showHomeDrawer(
            homeSections(DeviceRole.PARENT) { feature ->
                featureVisible(feature, disabledByChild)
            },
        ) { destination -> open(destination) }
    }

    private fun open(destination: HomeDestination) {
        when (destination) {
            HomeDestination.INBOX -> startActivity(ParentInboxActivity.intent(this))
            HomeDestination.CHAT -> startActivity(ChatActivity.intent(this))
            HomeDestination.LOCK_NOW -> confirmLock()
            HomeDestination.LOCATE -> locate()
            HomeDestination.RULES -> RulesEntry.launch(this)
            HomeDestination.CONTEXT_RULES -> startActivity(ContextRulesActivity.intent(this))
            HomeDestination.CATEGORIES,
            HomeDestination.APP_LIMITS,
            HomeDestination.APP_CONTROL,
            HomeDestination.WEB_FILTER,
            HomeDestination.PLACES,
            HomeDestination.ARRIVALS,
            HomeDestination.NIGHTSCOUT,
            HomeDestination.GOAL,
            HomeDestination.BONUS,
            HomeDestination.REQUEST_INBOX,
            HomeDestination.AUTO_APPROVE,
            -> RulesEntry.launchControl(this, destination)
            HomeDestination.THE_OTHER_PHONE -> startActivity(ParentDeviceActivity.intent(this))
            HomeDestination.FAMILY_DEVICES -> startActivity(FamilyDevicesActivity.intent(this))
            HomeDestination.AUDIT -> startActivity(AuditActivity.intent(this))
            HomeDestination.SETTINGS -> startActivity(SettingsActivity.intent(this))
            HomeDestination.REQUESTS,
            HomeDestination.WEEK,
            HomeDestination.WEB_ACTIVITY,
            HomeDestination.PRIVACY,
            -> error("$destination does not appear on a parent's home screen")
        }
    }

    /**
     * Every managed child's replicated `disabledFeatures`, read once per
     * [render] rather than once per feature checked.
     *
     * [featureVisible] used to call [PolicyReplication.read] itself, which
     * decodes this phone's entire replicated-state store from disk on every
     * call. [homeSections] checks more than one [dev.schrammel.protego_parentis.rules.Feature], so a screen with
     * two children and three feature checks meant six full-state decodes on
     * the UI thread every time this screen rendered — on every resume, and
     * again after every poll response — which is what made this dashboard
     * feel unresponsive as a family's replicated state grew. One pass here,
     * reused by every [featureVisible] call in [render], does the same reads
     * once.
     */
    private fun disabledFeaturesByChild(): List<Set<String>?> {
        val replication = PolicyReplication(this)
        return RosterStore(this).children().map { child -> replication.read(child.id)?.disabledFeatures }
    }

    /**
     * Today on the child's phone (RPT-1).
     *
     * The figures a parent opens the app for, in the order they are wanted: how
     * much is left, then how much has gone, then where. Identical to what the
     * child sees on their own dashboard — that symmetry is the product's central
     * promise, so this screen shows no more and no less.
     */
    private fun todayCard(): LinearLayout {
        val today = summaries.latest
        if (today == null) {
            return card {
                addView(
                    TextView(context).apply {
                        setText(R.string.parent_today_none)
                        styleAs(R.style.TextAppearance_Dementor_Subtitle)
                    },
                )
                addView(captionText(getString(R.string.parent_today_none_hint)))
            }
        }

        // The level travels as an ordinal so a parent phone needs no copy of the
        // rules to colour it. An unrecognised value reads as normal rather than as
        // an alarm — a newer child build must not make this screen shout.
        val level = BudgetLevel.entries.getOrNull(today.level) ?: BudgetLevel.NORMAL
        val progress = if (today.hasLimit) {
            percentUsed(today.usedMinutes, today.allowedMinutes + today.bonusMinutes)
        } else {
            null
        }

        return budgetSummaryCard(headlineFor(today), level, progress).apply {
            addView(
                statRow(
                    getString(R.string.parent_today_used),
                    formatLimit(today.usedMinutes),
                ),
            )

            if (today.topApps.isNotEmpty()) {
                addView(captionText(getString(R.string.parent_today_apps)))
                addCollapsibleRows(
                    this,
                    today.topApps.map { statRow(it.label, formatLimit(it.minutes)) })
            }

            addView(
                captionText(
                    getString(
                        R.string.parent_today_as_of,
                        formatTime(today.receivedAt),
                    ),
                ),
            )
        }
    }

    private fun headlineFor(today: DailySummary): String = when {
        !today.hasLimit -> getString(R.string.parent_today_no_limit)
        today.remainingMinutes <= 0 -> getString(R.string.home_used_up)
        else -> getString(R.string.parent_today_remaining) + " " + formatLimit(today.remainingMinutes)
    }

    /**
     * Confirmed, because it is the one button here that reaches across and changes
     * what the other phone is doing right now.
     */
    private fun confirmLock() {
        if (!reachable()) return
        confirmDialog(
            R.string.parent_lock_confirm_title,
            R.string.parent_lock_confirm_body,
            R.string.parent_row_lock,
        ) {
            sync.publishLockNow()
            Audit.record(this, AuditActor.PARENT, AuditAction.DEVICE_LOCKED)
            toast(R.string.parent_lock_sent)
        }
    }

    /**
     * Asks where the phone is.
     *
     * No confirmation: asking is not the irreversible part, and the child is told
     * every time regardless — which is what makes it reasonable to ask at all.
     */
    private fun locate() {
        if (!reachable()) return
        sync.publishLocateRequest()
        Audit.record(this, AuditActor.PARENT, AuditAction.LOCATE_REQUESTED)
        toast(R.string.parent_locate_sent)
        // Straight to where the answer lands. Asking from here and displaying
        // the result on another screen meant a position could arrive correctly
        // and never be seen — which reads exactly like the request having failed.
        startActivity(ParentDeviceActivity.intent(this))
    }

    /**
     * Whether there is anywhere for a command to go.
     *
     * Both of these publish and forget — the other phone picks the message up when
     * it next checks in, so there is no synchronous answer to report. That is fine
     * as long as there is a family to publish to. Without one, publish() quietly
     * does nothing while the toast said "Sent", which is a lie about the one kind
     * of action a parent most needs to be able to trust.
     *
     * It cannot promise delivery, and does not try to. It only refuses to claim
     * something was sent when there was nobody to send it to.
     */
    private fun reachable(): Boolean {
        if (sync.settings().isUsable) return true
        Toast.makeText(this, R.string.parent_not_connected_action, Toast.LENGTH_LONG).show()
        return false
    }

    /**
     * True once the managed phone was heard from at all, but not recently.
     *
     * [DailySummary.receivedAt] is this phone's own clock, stamped the moment
     * fresh telemetry last landed — see [DailySummaryStore.latest]'s own doc
     * comment — so it survives this activity being recreated, unlike
     * [lastCheckedAt] above, which only knows about a poll made in this
     * session.
     */
    private fun isCheckInStale(): Boolean {
        val heardAt = summaries.latest?.receivedAt ?: return false
        return java.time.Duration.between(heardAt, Instant.now()) > STALE_CHECKIN_THRESHOLD
    }

    private fun statusLine(configured: Boolean): String = when {
        !configured -> getString(R.string.parent_not_connected_hint)
        lastCheckedAt == null -> getString(R.string.parent_checking)
        else -> getString(
            R.string.parent_checked_at,
            formatTime(checkNotNull(lastCheckedAt)),
        )
    }

    /**
     * Refreshes in the background and re-renders with whatever arrived.
     *
     * The callback lands on the sync executor, so it hops back to the main thread
     * before touching a view.
     */
    private fun pollQuietly() {
        if (!sync.settings().isUsable) {
            // Nothing to poll, so a spinner a pull-to-refresh started must not
            // be left turning forever.
            refresh.isRefreshing = false
            return
        }
        sync.pollNow {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                lastCheckedAt = Instant.now()
                refresh.isRefreshing = false
                render()
            }
        }
    }

    companion object {
        /** How long since the last telemetry before a quiet inbox stops looking like good news. */
        private val STALE_CHECKIN_THRESHOLD: java.time.Duration = java.time.Duration.ofHours(24)

        fun intent(context: Context) = Intent(context, ParentHomeActivity::class.java)
    }
}
