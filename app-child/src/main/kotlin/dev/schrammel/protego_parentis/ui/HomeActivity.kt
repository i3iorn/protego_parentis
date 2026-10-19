package dev.schrammel.protego_parentis.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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
import dev.schrammel.protego_parentis.model.BudgetLevel
import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.Reason
import dev.schrammel.protego_parentis.model.RuleEffect
import dev.schrammel.protego_parentis.rules.GoalCoordinator
import dev.schrammel.protego_parentis.rules.LocalPolicyStore
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.RoleStore
import dev.schrammel.protego_parentis.sync.ParentPollWorker
import dev.schrammel.protego_parentis.sync.RealtimeSyncService
import dev.schrammel.protego_parentis.ui.rules.bedtimeRuleId
import dev.schrammel.protego_parentis.usage.InstalledApps

/**
 * The teen's dashboard (RPT-4).
 *
 * Shows exactly what a parent would see, in the same detail — that symmetry is
 * the product's central promise, so this screen hides nothing and softens nothing.
 *
 * Built in code rather than from a layout file, the same way [ParentHomeActivity]
 * and [SettingsActivity] are: one screen had a layout shell and the others did
 * not, which meant two places to change for one dashboard and a static title that
 * never joined the accessibility heading tree the built titles use.
 */
class HomeActivity : DementorActivity() {

    private lateinit var coordinator: EnforcementCoordinator
    private lateinit var installedApps: InstalledApps
    private lateinit var goals: GoalCoordinator

    private lateinit var column: LinearLayout
    private lateinit var permissionCard: LinearLayout
    private lateinit var summaryHolder: LinearLayout
    private lateinit var navigation: LinearLayout
    private lateinit var familyBannerHolder: LinearLayout
    private lateinit var activityBannerHolder: LinearLayout
    private lateinit var rulesList: LinearLayout
    private lateinit var blockedList: LinearLayout
    private lateinit var appList: LinearLayout

    /**
     * Set when this launch was handed to another screen.
     *
     * [finish] during [onCreate] usually skips straight to [onDestroy], but that
     * is not contractual, and every field below is a lateinit that was never
     * assigned. One boolean is cheaper than a crash on somebody else's Android.
     */
    private var routed = false

    /** See `res/values/config.xml`. */
    private val maxRows: Int by lazy { resources.getInteger(R.integer.dashboard_max_rows) }

    /**
     * Which blocked app [uninstallResult] is currently waiting to hear back
     * about — the launch intent itself carries no package, only [uninstallResult]'s
     * callback fires, so this is how it knows what to record.
     */
    private var pendingUninstall: PackageName? = null

    /**
     * The system's own uninstall-confirmation dialog for [pendingUninstall],
     * launched from [requestUninstall] (#207). Not this app's decision to make
     * silently or skip a confirmation for — Android's dialog is the same one
     * every other uninstall path on the phone already uses, and this screen
     * has no more authority to remove an app unconfirmed than any other.
     */
    private val uninstallResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val pkg = pendingUninstall
        pendingUninstall = null
        // RESULT_OK only when the system reports the uninstall actually
        // completed — a cancel, or the dialog simply being backed out of,
        // must not be recorded as though the app were gone.
        if (pkg != null && result.resultCode == RESULT_OK) {
            Audit.record(
                this,
                AuditActor.CHILD,
                AuditAction.APP_UNINSTALLED_BY_CHILD,
                subject = installedApps.labelFor(pkg),
            )
        }
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (routeByRole()) return

        coordinator = EnforcementCoordinator(this)
        installedApps = InstalledApps(this)
        goals = GoalCoordinator(this)

        buildLayout()
        buildNavigation()
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
     * The screen's skeleton, built once. Only the summary card and the three
     * lists below it are refilled on each render; the rest is fixed.
     */
    private fun buildLayout() {
        column = contentColumn()
        column.addView(screenTitle(getString(R.string.home_title)))

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

        summaryHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(summaryHolder)

        column.addView(sosButton())

        navigation = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = dimen(R.dimen.space_lg)
            }
        }
        column.addView(navigation)

        column.addView(sectionHeader(getString(R.string.home_rules_header)))
        rulesList = groupSurface()
        column.addView(rulesList)

        column.addView(sectionHeader(getString(R.string.home_would_block_header)))
        blockedList = groupSurface()
        column.addView(blockedList)

        column.addView(sectionHeader(getString(R.string.home_apps_header)))
        appList = groupSurface()
        column.addView(appList)

        showColumn(column)
    }

    /**
     * The emergency entry point (LOC-3).
     *
     * Above the navigation and outside any list, because it has to be found by
     * someone who is not reading. It works with every app suspended and the
     * day's allowance spent: a safety feature behind a limit is not one. Raising
     * it is guarded inside [SosActivity], not here — this button only opens the
     * screen that explains what will happen.
     */
    private fun sosButton(): Button = destructiveActionButton().apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
            topMargin = dimen(R.dimen.space_xl)
        }
        setTypeface(typeface, Typeface.BOLD)
        setText(R.string.sos_button)
        setOnClickListener { startActivity(SosActivity.intent(this@HomeActivity)) }
    }

    /** A group surface for a list of rows — the code equivalent of `Widget.Dementor.Group`. */
    private fun groupSurface(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.card)
        val side = dimen(R.dimen.card_padding)
        setPadding(side, dimen(R.dimen.space_sm), side, dimen(R.dimen.space_sm))
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
    }

    /**
     * Where this screen leads.
     *
     * Grouped the same way [ParentHomeActivity] is now (#78): whatever this
     * phone was opened to do, unlabelled; then Rules; Family; Openness;
     * Settings. What lands inside each group is [homeSections]' business, not
     * this method's — this only maps each [HomeDestination] to the screen it
     * opens.
     */
    private fun buildNavigation() {
        navigation.removeAllViews()

        homeSections(
            DeviceRole.CHILD,
            AppRegistry.childStores.featureFlags::isEnabled
        ).forEach { section ->
            addSection(
                navigation,
                section.label?.let(::getString),
                section.rows.map { entry ->
                    row(entry.title, entry.detail, showChevron = !entry.destination.isAction) {
                        open(entry.destination)
                    }
                },
            )
        }
    }

    private fun open(destination: HomeDestination) {
        when (destination) {
            HomeDestination.REQUESTS -> startActivity(RequestsActivity.intent(this))
            HomeDestination.CHAT -> startActivity(ChatActivity.intent(this))
            HomeDestination.WEEK -> startActivity(WeekActivity.intent(this))
            HomeDestination.WEB_ACTIVITY -> startActivity(WebActivityActivity.intent(this))
            HomeDestination.RULES -> RulesEntry.launch(this)
            HomeDestination.FAMILY_DEVICES -> startActivity(FamilyDevicesActivity.intent(this))
            HomeDestination.AUDIT -> startActivity(AuditActivity.intent(this))
            HomeDestination.PRIVACY -> startActivity(PrivacyActivity.intent(this))
            HomeDestination.SETTINGS -> startActivity(SettingsActivity.intent(this))
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
        showPendingCelebration()
        requestNotificationPermissionIfNeeded()
        render()
        // Safe to call repeatedly; the service ignores a duplicate start.
        if (coordinator.hasUsageAccess()) AppRegistry.ruleMutationSink.rulesChanged()
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

    private fun show(snapshot: EnforcementSnapshot?) {
        val needsPermission = snapshot == null

        permissionCard.visibility = if (needsPermission) View.VISIBLE else View.GONE
        summaryHolder.visibility = if (needsPermission) View.GONE else View.VISIBLE

        renderRules()

        if (snapshot == null) {
            clear(blockedList)
            clear(appList)
            return
        }

        renderSummary(snapshot)
        renderActiveRules(snapshot)
        renderCategoryBudgets(snapshot)
        renderBlocked(snapshot)
        renderUsage(snapshot)
    }

    /**
     * Which contextual rules are applying right now.
     *
     * Shown to the child unprompted: a phone that quietly behaves differently at
     * school than at home, with no way to see why, is the version of this feature
     * that feels like surveillance rather than a rule.
     */
    private fun renderActiveRules(snapshot: EnforcementSnapshot) {
        val active = snapshot.enforcement.activeContextRules
        if (active.isEmpty()) return

        rulesList.addView(
            line(getString(R.string.home_context_active, active.joinToString(", ") { it.name })),
        )
    }

    /**
     * The per-category allowances, appended to the rules list.
     *
     * No heading of their own: a category limit is a rule, and giving it a second
     * heading immediately under "My rules" split one idea in two. Nothing is shown
     * when no categories exist — an empty state saying so was a line of text
     * explaining the absence of a feature the family never turned on.
     */
    private fun renderCategoryBudgets(snapshot: EnforcementSnapshot) {
        val categories = snapshot.policy.categories
        if (categories.isEmpty()) return

        categories.sortedBy { it.name.lowercase() }.forEach { category ->
            val budget = snapshot.enforcement.categoryBudgets[category.id]
            rulesList.addView(
                line(
                    when {
                        budget == null || budget.isUnlimited ->
                            getString(R.string.home_category_no_limit, category.name)

                        budget.isExhausted ->
                            getString(R.string.home_category_used_up, category.name)

                        else -> getString(
                            R.string.home_category_row,
                            category.name,
                            formatDuration(budget.remaining),
                        )
                    },
                ),
            )
        }
    }

    /**
     * The one answer the screen exists to give, rebuilt into [summaryHolder] on
     * every render. The surface and the colour come from [budgetSummaryCard];
     * this method only decides the words and the bar's fill.
     */
    private fun renderSummary(snapshot: EnforcementSnapshot) {
        val budget = snapshot.enforcement.deviceBudget
        summaryHolder.removeAllViews()

        val headline: String
        val subline: String
        val progress: Int?

        when {
            budget.isUnlimited -> {
                headline = getString(R.string.home_no_limit_headline)
                subline = getString(R.string.home_used_no_limit, formatDuration(budget.used))
                progress = null
            }

            budget.level == BudgetLevel.EXHAUSTED -> {
                headline = getString(R.string.home_used_up)
                // Says what was used up, not just that it was: the headline is the
                // one thing read from across the room, and "time's up" with no
                // cause sends a reader hunting through the rules list below for
                // the number that already explains it.
                subline = getString(
                    R.string.home_used_up_detail,
                    formatDuration(budget.allowed + budget.bonus),
                )
                progress = 100
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
                note = note
            )
        )
    }

    /**
     * #106: both facts below used to live in [LocalPolicyStore] fields
     * ([LocalPolicyStore.dailyLimitMinutes], [LocalPolicyStore.bedtime]) that
     * only the now-deleted `LimitsActivity`/`ParentLimitActivity` wrote.
     * The unified rules screen writes a [dev.schrammel.protego_parentis.model.ContextRule]
     * instead, at the same well-known ids that screen and #105's migration
     * both use, so this summary reads those rules directly rather than a
     * field nothing edits any more.
     */
    private fun renderRules() {
        val container = clear(rulesList)
        val rules = AppRegistry.childStores.contextRules.rules

        val dailyLimit = rules.firstOrNull { it.id == LocalPolicyStore.MIGRATED_DAILY_LIMIT_ID }
        val budget = (dailyLimit?.effect as? RuleEffect.SetBudget)?.takeIf { dailyLimit.enabled }
        container.addView(
            line(
                if (budget == null) {
                    getString(R.string.home_rule_no_limit)
                } else {
                    getString(R.string.home_rule_limit, formatLimit(budget.budget.default.inWholeMinutes.toInt()))
                },
            ),
        )

        val bedtimeId = bedtimeRuleId()
        val bedtime = rules.firstOrNull { it.id == bedtimeId }
        val window = (bedtime?.condition as? Condition.DuringWindow)?.takeIf { bedtime.enabled }?.window
        container.addView(
            line(
                if (window == null) {
                    getString(R.string.home_rule_no_bedtime)
                } else {
                    getString(
                        R.string.home_rule_bedtime,
                        formatTime(window.start),
                        formatTime(window.end),
                    )
                },
            ),
        )
    }

    /**
     * #207: this list used to be inert on purpose — "those are the apps that
     * will not open, and offering a row that refuses is worse than not
     * offering one". But staring at an app you cannot open and cannot do
     * anything about is exactly what this list otherwise leaves a child
     * with, so each row is now tappable too — to a plain-words answer of *why*
     * ([showBlockedWhy]) rather than straight to Android's uninstall
     * confirmation. Uninstalling is still offered, on the trailing icon
     * alone: a parent who blocked something to pause it, not remove it,
     * still finds out — see [AuditAction.APP_UNINSTALLED_BY_CHILD].
     */
    private fun renderBlocked(snapshot: EnforcementSnapshot) {
        val container = clear(blockedList)
        val blocked = snapshot.enforcement.suspend + snapshot.enforcement.deferred

        if (blocked.isEmpty()) {
            container.addView(line(getString(R.string.home_nothing_blocked)))
            return
        }

        // Cap the list: with a device-wide limit hit, every installed app is in
        // here, and a wall of two hundred rows tells the reader nothing.
        blocked.entries.take(maxRows).forEach { (pkg, reason) ->
            val trailing = describe(reason) + " · " + getString(R.string.home_tap_for_details)
            container.addView(
                row(
                    pkg,
                    trailing,
                    trailingColor = R.color.state_critical,
                    uninstallable = true,
                    onWhy = { showBlockedWhy(pkg, reason) },
                ),
            )
        }
        if (blocked.size > maxRows) {
            val extra = blocked.size - maxRows
            container.addView(
                line(resources.getQuantityString(R.plurals.home_and_more, extra, extra)),
            )
        }
    }

    /**
     * Sends [pkg] to Android's own uninstall-confirmation dialog — the same
     * one Settings and every other uninstall path on the phone already uses,
     * not something this screen invents or skips a confirmation for.
     */
    private fun requestUninstall(pkg: PackageName) {
        pendingUninstall = pkg
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:${pkg.value}"))
        runCatching { uninstallResult.launch(intent) }.onFailure {
            pendingUninstall = null
            toast(R.string.home_cannot_uninstall)
        }
    }

    private fun renderUsage(snapshot: EnforcementSnapshot) {
        val container = clear(appList)
        val byPackage = snapshot.usage.snapshot.byPackage
            .entries
            .sortedByDescending { it.value }
            .take(maxRows)

        if (byPackage.isEmpty()) {
            container.addView(line(getString(R.string.home_no_usage)))
            return
        }

        // Openable, because a list of what you have been using that will not take
        // you to any of it is a list that answers a question nobody asked.
        byPackage.forEach { (pkg, time) ->
            container.addView(row(pkg, formatDuration(time), openable = true))
        }
    }

    private fun describe(reason: Reason): String {
        val until = reason.liftsAt?.let {
            getString(
                R.string.home_until,
                formatTime(it),
            )
        }
        // Resources rather than literals: these were the last English strings
        // left in the app, and a Swedish phone was showing three of them.
        // Rule and category names are prefixed, so "School hours" alone does
        // not read as some new jargon to a child — the name is the reason,
        // and saying which kind of thing it is makes that legible.
        val what = when (reason) {
            is Reason.ManuallyBlocked -> getString(R.string.reason_blocked)
            is Reason.UnmanagedBrowser -> getString(R.string.reason_unmanaged_browser)
            is Reason.DeviceBudgetExhausted -> getString(R.string.reason_daily_limit)
            is Reason.AppBudgetExhausted -> getString(R.string.reason_app_limit)
            is Reason.CategoryBudgetExhausted ->
                getString(R.string.reason_category_limit, reason.categoryName)
            is Reason.ContextRuleActive ->
                getString(R.string.reason_rule_active, reason.ruleName)
        }
        return listOfNotNull(what, until).joinToString(" ")
    }

    // ---------- small view helpers ----------

    private fun clear(container: LinearLayout): LinearLayout = container.apply { removeAllViews() }

    private fun line(text: String) = TextView(this).apply {
        this.text = text
        styleAs(R.style.TextAppearance_Dementor_Body)
        setPadding(0, dimen(R.dimen.space_xxs), 0, dimen(R.dimen.space_xxs))
    }

    private fun row(
        pkg: PackageName,
        trailing: String,
        openable: Boolean = false,
        trailingColor: Int? = null,
        uninstallable: Boolean = false,
        onWhy: (() -> Unit)? = null,
    ): View {
        val app = installedApps
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dimen(R.dimen.space_xs), 0, dimen(R.dimen.space_xs))

            if (openable) makeOpenable(pkg)
            if (onWhy != null) {
                // The row body explains the block; the trailing icon stays the
                // uninstall affordance, so the two never share one tap.
                withRipple()
                setOnClickListener { onWhy() }
            }

            addView(
                ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dimen(R.dimen.row_icon_small), dimen(R.dimen.row_icon_small)).apply {
                        marginEnd = dimen(R.dimen.space_md)
                    }
                    setImageDrawable(
                        runCatching {
                            packageManager.getApplicationIcon(pkg.value)
                        }.getOrNull(),
                    )
                    // Decorative: the label text right beside it already says the
                    // same thing, so a screen reader announcing "unlabeled image"
                    // on every single row is noise rather than information.
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
            )
            addView(
                TextView(context).apply {
                    text = app.labelFor(pkg)
                    styleAs(R.style.TextAppearance_Dementor_Body)
                    layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                },
            )
            addView(
                TextView(context).apply {
                    text = trailing
                    styleAs(R.style.TextAppearance_Dementor_Body)
                    trailingColor?.let { setTextColor(ContextCompat.getColor(context, it)) }
                },
            )
            if (uninstallable) {
                addView(
                    ImageView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            dimen(R.dimen.row_icon_small),
                            dimen(R.dimen.row_icon_small)
                        ).apply {
                            marginStart = dimen(R.dimen.space_sm)
                        }
                        setImageResource(R.drawable.ic_remove)
                        // The uninstall affordance is this icon alone now: the row's
                        // own tap explains the block, so the two answers never
                        // share one tap. Spoken as a control, not decoration.
                        contentDescription = getString(R.string.home_uninstall_desc, app.labelFor(pkg))
                        setOnClickListener { requestUninstall(pkg) }
                    },
                )
            }
        }
    }

    /**
     * Turns a usage row into a way back into the app it names.
     *
     * Deliberately not silent about failing. An app can be uninstalled between the
     * usage being recorded and the row being tapped, and it can be suspended by
     * this very app — in which case Android shows its own "app paused" dialog,
     * which is the correct answer and better than anything this screen could say.
     * What must not happen is a tap that does nothing at all, so the one case left
     * over — no launch intent, which is normal for a service or a widget host —
     * says so rather than looking broken.
     */
    private fun View.makeOpenable(pkg: PackageName) {
        withRipple()
        setOnClickListener {
            val launch = packageManager.getLaunchIntentForPackage(pkg.value)
            if (launch == null) {
                this@HomeActivity.toast(R.string.home_cannot_open)
                return@setOnClickListener
            }
            // Failure here is normal rather than exceptional: a suspended app
            // throws, and so does one uninstalled a moment ago.
            runCatching { startActivity(launch) }.onFailure {
                this@HomeActivity.toast(R.string.home_cannot_open)
            }
        }
    }

    /**
     * The one place the child can read, in plain words, why an app is closed:
     * the rule that is applying, when it ends, and the door to asking for a
     * change. Android's own "app paused" dialog says only that the app is
     * paused — this says what a child can do about it.
     */
    private fun showBlockedWhy(pkg: PackageName, reason: Reason) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.home_blocked_why_title, installedApps.labelFor(pkg)))
            .setMessage(getString(R.string.home_blocked_why_body, describe(reason)))
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.home_blocked_ask) { _, _ ->
                startActivity(RequestsActivity.intent(this))
            }
            .show()
    }

    private companion object {
        const val REQUEST_NOTIFICATIONS = 1
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
