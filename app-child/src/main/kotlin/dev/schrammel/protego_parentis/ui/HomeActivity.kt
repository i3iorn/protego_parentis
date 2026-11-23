package dev.schrammel.protego_parentis.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dev.schrammel.protego_parentis.AppRegistry
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.audit.ActivitySeenTracker
import dev.schrammel.protego_parentis.audit.Audit
import dev.schrammel.protego_parentis.enforcement.EnforcementCoordinator
import dev.schrammel.protego_parentis.enforcement.EnforcementSnapshot
import dev.schrammel.protego_parentis.format.formatDuration
import dev.schrammel.protego_parentis.model.AuditAction
import dev.schrammel.protego_parentis.model.AuditActor
import dev.schrammel.protego_parentis.rules.Feature
import dev.schrammel.protego_parentis.rules.GoalCoordinator
import dev.schrammel.protego_parentis.rules.LocalPolicyStore
import dev.schrammel.protego_parentis.rules.RequestCoordinator
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.RoleStore
import dev.schrammel.protego_parentis.sync.ParentPollWorker
import dev.schrammel.protego_parentis.sync.RealtimeSyncService

/**
 * The teen's dashboard (RPT-4).
 *
 * A dashboard answers one question and offers the rest on demand. This one
 * answers "how much time do I have, and what is stopping me?" and leaves
 * everything else — the rules, the history, the settings, the chat — in the
 * menu behind the header. It used to answer all of them at once, in a column
 * long enough that the answer at the top had to be scrolled back to.
 *
 * Shows exactly what a parent would see, in the same detail — that symmetry is
 * the product's central promise, so this screen hides nothing and softens
 * nothing.
 *
 * Built in code rather than from a layout file, the same way [ParentHomeActivity]
 * and [SettingsActivity] are: one screen had a layout shell and the others did
 * not, which meant two places to change for one dashboard and a static title that
 * never joined the accessibility heading tree the built titles use.
 */
class HomeActivity : DementorActivity(), HomeDestinationHandler {

    private lateinit var coordinator: EnforcementCoordinator
    private lateinit var goals: GoalCoordinator

    private lateinit var column: LinearLayout
    private lateinit var permissionCard: LinearLayout
    private lateinit var familyBannerHolder: LinearLayout
    private lateinit var activityBannerHolder: LinearLayout
    /**
     * Internal so [HomeActivityTest] can assert on what a render pass put
     * here; the screen is otherwise built and filled privately.
     */
    internal lateinit var lockStatusCardHolder: LinearLayout
    internal lateinit var summaryHolder: LinearLayout
    internal lateinit var allowancesHolder: LinearLayout
    internal lateinit var blockedCountHolder: LinearLayout

    /**
     * The menu button in the header, kept so the count of things waiting can
     * be put on it once the count is known.
     */
    private lateinit var menuButton: TextView

    /**
     * Set when this launch was handed to another screen.
     *
     * [finish] during [onCreate] usually skips straight to [onDestroy], but that
     * is not contractual, and every field below is a lateinit that was never
     * assigned. One boolean is cheaper than a crash on somebody else's Android.
     */
    private var routed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (routeByRole()) return

        coordinator = EnforcementCoordinator(this)
        goals = GoalCoordinator(this)

        buildLayout()
    }

    /**
     * Sends the launch on if this install is not the child's phone.
     *
     * This activity stays the launcher so that every existing notification intent
     * and `parentActivityName` chain keeps working; it just steps aside when the
     * phone belongs to a parent, or when nobody has said yet whose phone it is.
     *
     * Returns true when it has handed off and this screen should build nothing.
     */
    private fun routeByRole(): Boolean {
        val roles = RoleStore(this)
        val destination = when (roles.role) {
            null -> RoleSetupActivity.intent(this)
            DeviceRole.PARENT -> ParentHomeActivity.intent(this)
            DeviceRole.CHILD -> {
                // Left over if this phone was ever a parent's. Polling is folded
                // into the enforcement tick here, so the worker is redundant.
                ParentPollWorker.cancel(this)
                // The child side also streams so parent commands (lock, decisions)
                // land as they are published rather than on the enforcement tick.
                RealtimeSyncService.start(this)
                return false
            }
        }
        startActivity(destination)
        routed = true
        finish()
        return true
    }

    /**
     * The screen's skeleton, built once. Only the status cards and the two
     * short blocks below them are refilled on each render; the rest is fixed.
     */
    private fun buildLayout() {
        column = contentColumn()
        menuButton = menuButton(waiting = 0) { openMenu() }
        column.addView(
            screenTitle(
                getString(R.string.home_title),
                listOf(menuButton),
            ),
        )

        permissionCard = card {
            addView(
                TextView(context).apply {
                    text = getString(R.string.home_permission_title)
                    styleAs(R.style.TextAppearance_Dementor_Subtitle)
                },
            )
            addView(bodyText(getString(R.string.home_permission_body)))
            addView(
                primaryActionButton().apply {
                    setText(R.string.home_permission_button)
                    setOnClickListener {
                        // To the instructions rather than straight to Settings.
                        // Dropping somebody into a list of every app on the phone,
                        // with no note of what they are looking for or what the
                        // switch does, was the step that lost people.
                        startActivity(PermissionsActivity.intent(this@HomeActivity))
                    }
                },
            )
        }.apply { visibility = View.GONE }
        column.addView(permissionCard)

        // Filled in asynchronously, so they are placeholders until an answer
        // arrives — see renderFamilyBanner and renderActivityBanner.
        familyBannerHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(familyBannerHolder)
        activityBannerHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(activityBannerHolder)

        lockStatusCardHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(lockStatusCardHolder)

        summaryHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // A loading line rather than blank: the summary arrives off the main
        // thread, and an empty area reads as "nothing here" for the second or
        // two before the real answer lands.
        summaryHolder.addView(loadingLine())
        column.addView(summaryHolder)

        // The child's own allowances, one line each. This is the dashboard's
        // overview content: what is left in each of the buckets they spend
        // from. A category with no limit earns no line — there is no allowance
        // to have left.
        allowancesHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(allowancesHolder)

        // How much is blocked right now, as a count that opens the list. The
        // count is the glance; the names and the reasons are a visit.
        blockedCountHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(blockedCountHolder)

        // The two things a child comes here to do, rather than to look up.
        if (AppRegistry.childStores.featureFlags.isEnabled(Feature.REQUESTS)) {
            column.addView(
                navigationRow(
                    title = getString(R.string.home_open_requests),
                    subtitle = getString(R.string.home_open_requests_detail),
                ) { startActivity(RequestsActivity.intent(this)) },
            )
        }
        column.addView(
            navigationRow(
                title = getString(R.string.home_open_week),
                subtitle = getString(R.string.home_open_week_detail),
            ) { startActivity(WeekActivity.intent(this)) },
        )
        // The rules, on the dashboard rather than only in the menu: they are
        // the rules this phone is running, and the screen behind them opens
        // read-only to whoever is not the parent, so there is no PIN between
        // a child and the rules that apply to them.
        column.addView(
            navigationRow(
                title = getString(R.string.home_edit_rules),
                subtitle = getString(R.string.home_edit_rules_detail),
            ) { RulesEntry.launch(this) },
        )

        column.addView(sosButton())

        showColumn(column)
    }

    /**
     * The emergency entry point (LOC-3).
     *
     * Above everything else, because it has to be found by someone who is not
     * reading. It works with every app suspended and the day's allowance spent:
     * a safety feature behind a limit is not one. Raising it is guarded inside
     * [SosActivity], not here — this button only opens the screen that explains
     * what will happen.
     */
    private fun sosButton(): Button = destructiveActionButton().apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
            topMargin = dimen(R.dimen.space_xl)
        }
        setTypeface(typeface, Typeface.BOLD)
        setText(R.string.sos_button)
        setOnClickListener { startActivity(SosActivity.intent(this@HomeActivity)) }
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
            homeSections(
                DeviceRole.CHILD,
                AppRegistry.childStores.featureFlags::isEnabled,
            ),
            this,
        )
    }

    override fun open(destination: HomeDestination) {
        when (destination) {
            HomeDestination.REQUESTS -> startActivity(RequestsActivity.intent(this))
            HomeDestination.CHAT -> startActivity(ChatActivity.intent(this))
            HomeDestination.WEEK -> startActivity(WeekActivity.intent(this))
            HomeDestination.WEB_ACTIVITY -> startActivity(WebActivityActivity.intent(this))
            HomeDestination.CONTEXT_RULES -> startActivity(ContextRulesActivity.intent(this))
            // The rest are controls the child can open on this phone itself —
            // launchControl resolves to this phone's own ruleset when none is named.
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
            HomeDestination.FAMILY_DEVICES -> startActivity(FamilyDevicesActivity.intent(this))
            HomeDestination.AUDIT -> startActivity(AuditActivity.intent(this))
            HomeDestination.PRIVACY -> startActivity(PrivacyActivity.intent(this))
            HomeDestination.SETTINGS -> startActivity(SettingsActivity.intent(this))
            HomeDestination.RULES,
            HomeDestination.INBOX,
            HomeDestination.LOCK_NOW,
            HomeDestination.LOCATE,
            HomeDestination.THE_OTHER_PHONE,
            -> error("$destination does not appear on the managed phone's home screen")
        }
    }

    /**
     * Whether the family is finished being set up.
     *
     * Shown on the child's phone too, not just the parent's. On a phone being set
     * up for the first time this device is the one that approves the founding
     * parent, and the person doing it is standing right there — telling only the
     * parent's phone would be telling the wrong half of the room.
     */
    private fun renderFamilyBanner() {
        familyBannerHolder.removeAllViews()
        Background.load(this, { FamilyStatus.read(this) to RuleVersionWarning.read(this) }) { (snapshot, versions) ->
            ruleVersionBanner(versions) { startActivity(FamilyDevicesActivity.intent(this)) }
                ?.let(familyBannerHolder::addView)
            familyBanner(snapshot) { startActivity(FamilyDevicesActivity.intent(this)) }
                ?.let(familyBannerHolder::addView)
        }
    }

    override fun onResume() {
        super.onResume()
        if (routed) return
        renderFamilyBanner()
        renderActivityBanner()
        renderMenuBadge()
        showPendingCelebration()
        requestNotificationPermissionIfNeeded()
        render()
        // Safe to call repeatedly; the service ignores a duplicate start.
        if (coordinator.hasUsageAccess()) AppRegistry.ruleMutationSink.rulesChanged()
    }

    /**
     * The count of requests waiting for an answer, on the menu button.
     *
     * A menu hides what is in it, and a badge is what says it is not empty: a
     * child who has asked for more time and is waiting to hear, should not have
     * to open a menu to find out.
     */
    private fun renderMenuBadge() {
        Background.load(this, { RequestCoordinator(this).pending().size }) { waiting ->
            menuButton.text = if (waiting > 0) "☰ $waiting" else "☰"
            menuButton.contentDescription = if (waiting > 0) {
                resources.getQuantityString(R.plurals.home_menu_waiting, waiting, waiting)
            } else {
                getString(R.string.home_menu)
            }
        }
    }

    /**
     * Whether anything has happened since this phone last opened the shared
     * history — see [ActivitySeenTracker]. A pointer to [AuditActivity], not a
     * second copy of what it shows: this app already has one place that lists
     * everything that happened, and a screen that duplicated it would be two
     * histories a family could disagree about.
     */
    private fun renderActivityBanner() {
        activityBannerHolder.removeAllViews()
        Background.load(this, {
            val entries = Audit.entries(this)
            ActivitySeenTracker(this).unseen(entries).size
        }) { count ->
            activityBanner(count) { startActivity(AuditActivity.intent(this)) }
                ?.let(activityBannerHolder::addView)
        }
    }

    /**
     * Opening the app is the other way a prize gets seen, for anyone who swiped the
     * notification away. Returns true when the celebration took over the screen.
     */
    private fun showPendingCelebration() {
        Background.load(this, { runCatching { goals.refresh() }.getOrNull() }) { earned ->
            if (earned != null) startActivity(CelebrationActivity.intent(this))
        }
    }

    /**
     * Without this the warning ladder is silent: the service still runs, but from
     * Android 13 the notifications it posts are dropped until the user agrees.
     */
    private fun requestNotificationPermissionIfNeeded() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATIONS,
            )
        }
    }

    /**
     * Evaluating reads preferences, replays a day of usage events and walks the
     * installed-app list. All of that ran on the main thread on every resume.
     */
    private fun render() {
        Background.load(this, { coordinator.evaluate() }, ::show)
    }

    /**
     * Internal so [HomeActivityTest] can drive a rendering pass directly: the
     * async [render] path makes a render test a race, and the snapshot is the
     * thing under test, not the executor that fetches it.
     */
    internal fun show(snapshot: EnforcementSnapshot?) {
        val needsPermission = snapshot == null

        permissionCard.visibility = if (needsPermission) View.VISIBLE else View.GONE
        summaryHolder.visibility = if (needsPermission) View.GONE else View.VISIBLE

        if (snapshot == null) {
            clear(allowancesHolder)
            clear(blockedCountHolder)
            clear(lockStatusCardHolder)
            return
        }

        renderLockStatusCard(snapshot)
        renderSummary(snapshot)
        renderAllowances(snapshot)
        renderBlockedCount(snapshot)
    }

    /**
     * The one card that answers "why is my phone locked" and "when does it
     * open again" — the first thing on the screen. It renders only when the
     * phone is restricted; a card standing over "everything is fine" is noise.
     */
    private fun renderLockStatusCard(snapshot: EnforcementSnapshot) {
        lockStatusCardHolder.removeAllViews()
        lockStatusCard(snapshot)?.let { lockStatusCardHolder.addView(it) }
    }

    /**
     * The one answer the screen exists to give, rebuilt into [summaryHolder] on
     * every render. The surface and the colour come from [budgetSummaryCard];
     * this method only decides the words and the bar's fill.
     *
     * While anything is restricted, the lock status card above is the status
     * card: it carries the reason, the countdown and the day's remaining time
     * together. A second card beside it — the same numbers in a different
     * shape — is what made this screen read as two surfaces fighting over one
     * fact, so this one steps aside and leaves. The numbers it would have
     * carried live on the week screen.
     */
    private fun renderSummary(snapshot: EnforcementSnapshot) {
        val budget = snapshot.enforcement.deviceBudget
        summaryHolder.removeAllViews()

        if (lockStatusCardHolder.childCount > 0) {
            summaryHolder.visibility = View.GONE
            return
        }
        summaryHolder.visibility = View.VISIBLE

        val headline: String
        val subline: String
        val progress: Int?

        when {
            budget.isUnlimited -> {
                headline = getString(R.string.home_no_limit_headline)
                subline = getString(R.string.home_used_no_limit, formatDuration(budget.used))
                progress = null
            }

            else -> {
                headline = getString(R.string.home_remaining, formatDuration(budget.remaining))
                subline = getString(
                    R.string.home_used_of,
                    formatDuration(budget.used),
                    formatDuration(budget.allowed + budget.bonus),
                )
                progress = percentUsed(
                    budget.used.inWholeMinutes.toInt(),
                    (budget.allowed + budget.bonus).inWholeMinutes.toInt(),
                )
            }
        }

        val note = getString(
            if (snapshot.monitorOnly) R.string.home_monitor_only else R.string.home_managed,
        )
        summaryHolder.addView(
            budgetSummaryCard(
                headline,
                budget.level,
                progress,
                subline = subline,
                note = note,
            )
        )
    }

    /**
     * The child's own allowances, one line each.
     *
     * A category limit is a rule, and a rule belongs in the rules screen; what
     * belongs on the dashboard is the allowance it leaves behind. A category
     * with no limit has no allowance and earns no line, and a category whose
     * allowance is spent says so rather than showing a zero.
     */
    private fun renderAllowances(snapshot: EnforcementSnapshot) {
        val container = clear(allowancesHolder)
        val categories = snapshot.policy.categories
            .sortedBy { it.name.lowercase() }
            .mapNotNull { category ->
                val budget = snapshot.enforcement.categoryBudgets[category.id] ?: return@mapNotNull null
                if (budget.isUnlimited) return@mapNotNull null
                category to budget
            }

        if (categories.isEmpty()) {
            // No heading over nothing: the block hides with the lines it holds.
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        container.addView(sectionHeader(getString(R.string.home_allowances_header)))
        categories.forEach { (category, budget) ->
            container.addView(
                line(
                    if (budget.isExhausted) {
                        getString(R.string.home_category_used_up, category.name)
                    } else {
                        getString(R.string.home_category_row, category.name, formatDuration(budget.remaining))
                    },
                ),
            )
        }
    }

    /**
     * How much is blocked right now, as a count that opens the list.
     *
     * The count is the glance; the names and the reasons are a visit, and the
     * list is a screen of its own so that it can be the whole list rather than
     * four rows and a "and N more" line.
     */
    private fun renderBlockedCount(snapshot: EnforcementSnapshot) {
        val container = clear(blockedCountHolder)
        val blocked = snapshot.enforcement.suspend.size + snapshot.enforcement.deferred.size
        if (blocked == 0) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        container.addView(
            TextView(this).apply {
                text = resources.getQuantityString(R.plurals.home_blocked_count, blocked, blocked)
                styleAs(R.style.TextAppearance_Dementor_Body)
                setPadding(0, dimen(R.dimen.space_xxs), 0, dimen(R.dimen.space_xxs))
                withRipple()
                setOnClickListener { startActivity(BlockedAppsActivity.intent(this@HomeActivity)) }
            },
        )
    }

    // ---------- small view helpers ----------

    private fun clear(container: LinearLayout): LinearLayout = container.apply { removeAllViews() }

    private fun line(text: String) = TextView(this).apply {
        this.text = text
        styleAs(R.style.TextAppearance_Dementor_Body)
        setPadding(0, dimen(R.dimen.space_xxs), 0, dimen(R.dimen.space_xxs))
    }

    /** A quiet "Loading…" stand-in for a list that is filled off the main thread. */
    private fun loadingLine() = TextView(this).apply {
        setText(R.string.home_loading)
        styleAs(R.style.TextAppearance_Dementor_Caption)
        setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        setPadding(0, dimen(R.dimen.space_xxs), 0, dimen(R.dimen.space_xxs))
    }

    companion object {
        private const val REQUEST_NOTIFICATIONS = 1001
    }
}
