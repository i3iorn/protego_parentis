package dev.schrammel.dementor_watch.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dev.schrammel.dementor_watch.R
import dev.schrammel.dementor_watch.enforcement.EnforcementCoordinator
import dev.schrammel.dementor_watch.enforcement.EnforcementService
import dev.schrammel.dementor_watch.enforcement.EnforcementSnapshot
import dev.schrammel.dementor_watch.model.BudgetLevel
import dev.schrammel.dementor_watch.model.PackageName
import dev.schrammel.dementor_watch.model.Reason
import dev.schrammel.dementor_watch.rules.LocalPolicyStore
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
class HomeActivity : AppCompatActivity() {

    private lateinit var coordinator: EnforcementCoordinator
    private lateinit var installedApps: InstalledApps
    private lateinit var store: LocalPolicyStore

    private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern(TIME_PATTERN)

    /** See `res/values/config.xml`. */
    private val maxRows: Int by lazy { resources.getInteger(R.integer.dashboard_max_rows) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        coordinator = EnforcementCoordinator(this)
        installedApps = InstalledApps(this)
        store = LocalPolicyStore(this)

        findViewById<Button>(R.id.permission_button).setOnClickListener {
            startActivity(UsageAccess.settingsIntent())
        }
        findViewById<Button>(R.id.open_week).setOnClickListener {
            startActivity(WeekActivity.intent(this))
        }
        findViewById<Button>(R.id.edit_rules).setOnClickListener {
            startActivity(Intent(this, RulesActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        requestNotificationPermissionIfNeeded()
        render()
        // Safe to call repeatedly; the service ignores a duplicate start.
        if (coordinator.hasUsageAccess()) EnforcementService.start(this)
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

    private fun render() {
        val snapshot = coordinator.evaluate()
        val needsPermission = snapshot == null

        findViewById<View>(R.id.permission_card).visibility =
            if (needsPermission) View.VISIBLE else View.GONE
        findViewById<View>(R.id.summary_card).visibility =
            if (needsPermission) View.GONE else View.VISIBLE

        renderRules()

        if (snapshot == null) {
            clear(R.id.category_budgets)
            clear(R.id.blocked_list)
            clear(R.id.app_list)
            return
        }

        renderSummary(snapshot)
        renderCategoryBudgets(snapshot)
        renderBlocked(snapshot)
        renderUsage(snapshot)
    }

    private fun renderCategoryBudgets(snapshot: EnforcementSnapshot) {
        val container = clear(R.id.category_budgets)
        val categories = snapshot.policy.categories

        if (categories.isEmpty()) {
            container.addView(line(getString(R.string.rules_no_categories)))
            return
        }

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
            }

            budget.level == BudgetLevel.EXHAUSTED -> {
                headline.text = getString(R.string.home_used_up)
                subline.text = getString(R.string.home_resets)
                bar.visibility = View.VISIBLE
                bar.progress = 100
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
        val what = when (reason) {
            is Reason.ManuallyBlocked -> "Blocked"
            is Reason.ScheduleActive -> reason.scheduleName
            is Reason.DeviceBudgetExhausted -> "Daily limit"
            is Reason.AppBudgetExhausted -> "App limit"
            is Reason.CategoryBudgetExhausted -> reason.categoryName
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
