package dev.schrammel.dementor_watch.ui

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dev.schrammel.dementor_watch.R
import dev.schrammel.dementor_watch.enforcement.EnforcementCoordinator
import dev.schrammel.dementor_watch.enforcement.EnforcementService
import dev.schrammel.dementor_watch.enforcement.EnforcementSnapshot
import dev.schrammel.dementor_watch.model.BudgetLevel
import dev.schrammel.dementor_watch.model.PackageName
import dev.schrammel.dementor_watch.model.Reason
import dev.schrammel.dementor_watch.rules.Feature
import dev.schrammel.dementor_watch.rules.FeatureFlags
import dev.schrammel.dementor_watch.rules.GoalCoordinator
import dev.schrammel.dementor_watch.rules.LocalPolicyStore
import dev.schrammel.dementor_watch.security.DeviceRole
import dev.schrammel.dementor_watch.security.RoleStore
import dev.schrammel.dementor_watch.sync.ParentPollWorker
import dev.schrammel.dementor_watch.usage.InstalledApps
import dev.schrammel.dementor_watch.usage.UsageAccess
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Duration

/**
 * The teen's dashboard (RPT-4).
 *
 * Shows exactly what a parent would see, in the same detail — that symmetry is
 * the product's central promise, so this screen hides nothing and softens nothing.
 */
class HomeActivity : DementorActivity() {

    private lateinit var coordinator: EnforcementCoordinator
    private lateinit var installedApps: InstalledApps
    private lateinit var store: LocalPolicyStore
    private lateinit var goals: GoalCoordinator

    private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern(TIME_PATTERN)

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (routeByRole()) return
        setContentView(R.layout.activity_home)

        coordinator = EnforcementCoordinator(this)
        installedApps = InstalledApps(this)
        store = LocalPolicyStore(this)
        goals = GoalCoordinator(this)

        findViewById<Button>(R.id.permission_button).setOnClickListener {
            startActivity(UsageAccess.settingsIntent())
        }
        findViewById<Button>(R.id.sos_button).setOnClickListener {
            startActivity(SosActivity.intent(this))
        }
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
                return false
            }
        }
        startActivity(destination)
        routed = true
        finish()
        return true
    }

    /**
     * Where this screen leads.
     *
     * Grouped rather than listed. Eight rows in one card is a wall, and the three
     * kinds of destination here are genuinely different: things to look at, the
     * two screens that make this app something a person can agree to, and the
     * settings nobody opens twice. Each row still says what is behind it — with
     * this many, "History" alone does not tell anyone what they would find.
     */
    private fun buildNavigation() {
        val navigation = findViewById<LinearLayout>(R.id.navigation)
        navigation.removeAllViews()

        val on = FeatureFlags(this)

        // Unlabelled: the screen title has already said this is your phone, and a
        // heading over the first group would only be naming the obvious.
        addSection(
            navigation,
            null,
            buildList {
                if (on.isEnabled(Feature.REQUESTS)) {
                    add(
                        row(R.string.home_open_requests, R.string.home_open_requests_detail) {
                            startActivity(RequestsActivity.intent(this@HomeActivity))
                        },
                    )
                }
                if (on.isEnabled(Feature.CHAT)) {
                    add(
                        row(R.string.chat_title, R.string.chat_row_detail) {
                            startActivity(ChatActivity.intent(this@HomeActivity))
                        },
                    )
                }
                add(
                    row(R.string.home_open_week, R.string.home_open_week_detail) {
                        startActivity(WeekActivity.intent(this@HomeActivity))
                    },
                )
                if (on.isEnabled(Feature.WEB_ACTIVITY)) {
                    add(
                        row(
                            R.string.rules_row_web_activity,
                            R.string.rules_row_web_activity_detail,
                        ) { startActivity(WebActivityActivity.intent(this@HomeActivity)) },
                    )
                }
            },
        )

        // Never hidden, and given their own heading rather than buried among the
        // settings. These two are what a child is owed in exchange for the rest of
        // this, and a promise filed under "other" is not much of a promise.
        addSection(
            navigation,
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
            navigation,
            getString(R.string.home_group_settings),
            listOf(
                row(R.string.notify_title, R.string.notify_row_detail) {
                    startActivity(NotificationSettingsActivity.intent(this))
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

    private fun row(titleRes: Int, detailRes: Int, onClick: () -> Unit) =
        navigationRow(getString(titleRes), getString(detailRes), onClick)

    override fun onResume() {
        super.onResume()
        if (routed) return
        showPendingCelebration()
        requestNotificationPermissionIfNeeded()
        render()
        // Safe to call repeatedly; the service ignores a duplicate start.
        if (coordinator.hasUsageAccess()) EnforcementService.start(this)
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

        findViewById<View>(R.id.permission_card).visibility =
            if (needsPermission) View.VISIBLE else View.GONE
        findViewById<View>(R.id.summary_card).visibility =
            if (needsPermission) View.GONE else View.VISIBLE

        renderRules()

        if (snapshot == null) {
            clear(R.id.blocked_list)
            clear(R.id.app_list)
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

        findViewById<LinearLayout>(R.id.rules_list).addView(
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
        val container = findViewById<LinearLayout>(R.id.rules_list)
        val categories = snapshot.policy.categories
        if (categories.isEmpty()) return

        categories.sortedBy { it.name.lowercase() }.forEach { category ->
            val budget = snapshot.enforcement.categoryBudgets[category.id]
            container.addView(
                line(
                    when {
                        budget == null || budget.isUnlimited ->
                            getString(R.string.home_category_no_limit, category.name)

                        budget.isExhausted ->
                            getString(R.string.home_category_used_up, category.name)

                        else -> getString(
                            R.string.home_category_row,
                            category.name,
                            Format.duration(budget.remaining),
                        )
                    },
                ),
            )
        }
    }

    private fun renderSummary(snapshot: EnforcementSnapshot) {
        val budget = snapshot.enforcement.deviceBudget
        val headline = findViewById<TextView>(R.id.headline)
        val subline = findViewById<TextView>(R.id.subline)
        val bar = findViewById<ProgressBar>(R.id.budget_bar)

        when {
            budget.isUnlimited -> {
                headline.text = getString(R.string.home_no_limit_headline)
                subline.text = getString(
                    R.string.home_used_no_limit,
                    Format.duration(budget.used),
                )
                bar.visibility = View.GONE
                tintText(headline, BudgetLevel.NORMAL)
            }

            budget.level == BudgetLevel.EXHAUSTED -> {
                headline.text = getString(R.string.home_used_up)
                subline.text = getString(R.string.home_resets)
                bar.visibility = View.VISIBLE
                bar.progress = 100
                tint(bar, BudgetLevel.EXHAUSTED)
                tintText(headline, BudgetLevel.EXHAUSTED)
            }

            else -> {
                headline.text = getString(
                    R.string.home_remaining,
                    Format.duration(budget.remaining),
                )
                subline.text = getString(
                    R.string.home_used_of,
                    Format.duration(budget.used),
                    Format.duration(budget.allowed + budget.bonus),
                )
                bar.visibility = View.VISIBLE
                bar.progress = percentUsed(budget.used, budget.allowed + budget.bonus)
                tint(bar, budget.level)
                tintText(headline, budget.level)
            }
        }

        findViewById<TextView>(R.id.mode_note).setText(
            if (snapshot.monitorOnly) R.string.home_monitor_only else R.string.home_managed,
        )
    }

    private fun renderRules() {
        val container = clear(R.id.rules_list)

        val limit = store.dailyLimitMinutes
        container.addView(
            line(
                if (limit <= 0) {
                    getString(R.string.home_rule_no_limit)
                } else {
                    getString(R.string.home_rule_limit, Format.limit(limit))
                },
            ),
        )

        val bedtime = store.bedtime
        container.addView(
            line(
                if (!bedtime.enabled) {
                    getString(R.string.home_rule_no_bedtime)
                } else {
                    getString(
                        R.string.home_rule_bedtime,
                        Format.time(bedtime.start),
                        Format.time(bedtime.end),
                    )
                },
            ),
        )
    }

    private fun renderBlocked(snapshot: EnforcementSnapshot) {
        val container = clear(R.id.blocked_list)
        val blocked = snapshot.enforcement.suspend + snapshot.enforcement.deferred

        if (blocked.isEmpty()) {
            container.addView(line(getString(R.string.home_nothing_blocked)))
            return
        }

        // Cap the list: with a device-wide limit hit, every installed app is in
        // here, and a wall of two hundred rows tells the reader nothing.
        blocked.entries.take(maxRows).forEach { (pkg, reason) ->
            container.addView(row(pkg, describe(reason)))
        }
        if (blocked.size > maxRows) {
            val extra = blocked.size - maxRows
            container.addView(
                line(resources.getQuantityString(R.plurals.home_and_more, extra, extra)),
            )
        }
    }

    private fun renderUsage(snapshot: EnforcementSnapshot) {
        val container = clear(R.id.app_list)
        val byPackage = snapshot.usage.snapshot.byPackage
            .entries
            .sortedByDescending { it.value }
            .take(maxRows)

        if (byPackage.isEmpty()) {
            container.addView(line(getString(R.string.home_no_usage)))
            return
        }

        byPackage.forEach { (pkg, time) ->
            container.addView(row(pkg, Format.duration(time)))
        }
    }

    private fun describe(reason: Reason): String {
        val until = reason.liftsAt?.let {
            getString(
                R.string.home_until,
                timeFormat.format(it.atZone(ZoneId.systemDefault())),
            )
        }
        // Resources rather than literals: these were the last English strings
        // left in the app, and a Swedish phone was showing three of them.
        val what = when (reason) {
            is Reason.ManuallyBlocked -> getString(R.string.reason_blocked)
            is Reason.UnmanagedBrowser -> getString(R.string.reason_unmanaged_browser)
            is Reason.ScheduleActive -> reason.scheduleName
            is Reason.DeviceBudgetExhausted -> getString(R.string.reason_daily_limit)
            is Reason.AppBudgetExhausted -> getString(R.string.reason_app_limit)
            is Reason.CategoryBudgetExhausted -> reason.categoryName
            is Reason.ContextRuleActive -> reason.ruleName
        }
        return listOfNotNull(what, until).joinToString(" ")
    }

    // ---------- small view helpers ----------

    private fun clear(id: Int): LinearLayout =
        findViewById<LinearLayout>(id).apply { removeAllViews() }

    private fun line(text: String) = TextView(this).apply {
        this.text = text
        styleAs(R.style.TextAppearance_Dementor_Body)
        setPadding(0, dimen(R.dimen.space_xxs), 0, dimen(R.dimen.space_xxs))
    }

    private fun row(pkg: PackageName, trailing: String): View {
        val app = installedApps
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dimen(R.dimen.space_xs), 0, dimen(R.dimen.space_xs))

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
                },
            )
        }
    }

    /**
     * State as colour as well as number, so "nearly out" reads at a glance rather
     * than having to be worked out from a figure.
     */
    private fun colourFor(level: BudgetLevel): Int = when (level) {
        BudgetLevel.NORMAL -> R.color.state_ok
        BudgetLevel.NOTICE, BudgetLevel.GRACE -> R.color.state_warning
        BudgetLevel.EXHAUSTED -> R.color.state_critical
    }

    private fun tint(bar: ProgressBar, level: BudgetLevel) {
        bar.progressTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, colourFor(level)))
    }

    /**
     * The number itself takes the state colour, not only the bar beneath it.
     * "Nearly out" should be readable from across a room.
     */
    private fun tintText(view: TextView, level: BudgetLevel) {
        view.setTextColor(ContextCompat.getColor(this, colourFor(level)))
    }

    private fun percentUsed(used: Duration, total: Duration): Int {
        if (total <= Duration.ZERO || total.isInfinite()) return 0
        return ((used / total) * 100).toInt().coerceIn(0, 100)
    }

    private companion object {
        const val REQUEST_NOTIFICATIONS = 1
        const val TIME_PATTERN = "HH:mm"
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
