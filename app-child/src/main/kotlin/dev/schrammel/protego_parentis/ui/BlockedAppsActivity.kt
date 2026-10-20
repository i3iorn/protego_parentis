package dev.schrammel.protego_parentis.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.enforcement.EnforcementCoordinator
import dev.schrammel.protego_parentis.enforcement.EnforcementSnapshot
import dev.schrammel.protego_parentis.model.GrantTarget
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.Reason
import dev.schrammel.protego_parentis.rules.RequestCoordinator
import dev.schrammel.protego_parentis.usage.InstalledApps

/**
 * Everything this phone is refusing right now, in full.
 *
 * The dashboard says how many apps are blocked, because that is a number worth
 * glancing at; the names and the reasons are what a reader actually wants when
 * the number is not zero. They used to be four rows and a "and N more" line
 * on the dashboard, which is the worst of both: too long to read, too short
 * to be the list. Here the list is the whole list, and it scrolls.
 *
 * A row answers rather than hints: what is blocked is the app's name, why is
 * the reason the engine gives, and until is the moment it lifts. Tapping the
 * row opens the same why-dialog the dashboard's rows opened, and the
 * uninstall affordance stays an icon of its own so the two answers never share
 * one tap.
 */
class BlockedAppsActivity : DementorActivity() {

    private lateinit var installedApps: InstalledApps
    private lateinit var column: LinearLayout
    private lateinit var list: LinearLayout

    private var pendingUninstall: PackageName? = null

    /**
     * The app a block notification named, waiting for a snapshot to explain
     * it. The list is on this screen now, so the answer arrives here.
     */
    private var pendingBlockedPkg: PackageName? = null

    /**
     * The system's own uninstall-confirmation dialog, launched from
     * [requestUninstall]. Not this app's decision to make: the child is
     * removing an app from their own phone, and the platform's own question is
     * the right one to ask.
     */
    private val uninstallResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val pkg = pendingUninstall
        pendingUninstall = null
        if (pkg == null || result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installedApps = InstalledApps(this)
        pendingBlockedPkg = intent.getStringExtra(EXTRA_BLOCKED_PACKAGE)?.let(::PackageName)
        column = contentColumn()
        column.addView(screenTitle(getString(R.string.home_would_block_header)))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(list)
        showColumn(column)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        Background.load(this, { EnforcementCoordinator(this).evaluate() }, ::show)
    }

    /** Internal so a test can drive a rendering pass directly. */
    internal fun show(snapshot: EnforcementSnapshot?) {
        list.removeAllViews()
        if (snapshot == null) {
            list.addView(line(getString(R.string.home_loading)))
            return
        }

        val blocked = snapshot.enforcement.suspend + snapshot.enforcement.deferred
        if (blocked.isEmpty()) {
            list.addView(line(getString(R.string.home_nothing_blocked)))
            return
        }

        // When the device budget is spent, the ask belongs next to the block
        // that caused it: the same moment the reader discovers the block is
        // the moment they would want more time. It appears only when the
        // request flow would actually take the ask; a row that refuses is
        // worse than no row.
        if (snapshot.enforcement.deviceBudget.isExhausted) {
            val decisions = RequestCoordinator(this).canSubmit(GrantTarget.Device)
            if (decisions.isAllowed) {
                list.addView(askForMoreTimeRow())
            }
        }

        // The whole list, not a sample of it: this screen exists to be
        // scrolled, and a cap here would be the dashboard's cap all over
        // again.
        blocked.forEach { (pkg, reason) ->
            list.addView(
                row(
                    pkg,
                    trailing = "",
                    bodyLines = blockedRowBody(reason),
                    uninstallable = true,
                    onWhy = { showBlockedWhy(pkg, reason) },
                ),
            )
        }

        // A notification tapped mid-block lands here: once the list is in, open
        // the why-dialog for the app it named - but only while the block still
        // stands. A launch that arrives after the rule lifted has nothing to
        // explain, and a dialog saying "you're blocked" over a working phone
        // would be the notification lying.
        pendingBlockedPkg?.let { pkg ->
            pendingBlockedPkg = null
            snapshot.enforcement.suspend[pkg]?.let { reason ->
                showBlockedWhy(pkg, reason)
            }
        }
    }

    /**
     * The pinned "Ask for more time" row. Tapping it opens the request inbox
     * ([RequestsActivity]) directly, so the ask is one step from the block
     * rather than two.
     */
    private fun askForMoreTimeRow(): View = row(
        pkg = PackageName(ASK_MORE_TIME_TAG),
        trailing = getString(R.string.home_blocked_ask),
        bodyLines = listOf(getString(R.string.home_ask_more_time_body)),
        onWhy = { startActivity(RequestsActivity.intent(this)) },
    )

    /**
     * The blocked row's answer, as labelled lines: WHAT is blocked is the
     * app's own name above, WHY is the reason the engine gives, and UNTIL is
     * the moment it lifts. A row is an answer, not a label plus a hint to tap
     * somewhere else for one.
     */
    private fun blockedRowBody(reason: Reason): List<String> = buildList {
        add(getString(R.string.home_blocked_why, reasonPhrase(reason)))
        reason.liftsAt?.let { add(getString(R.string.home_blocked_until, formatTime(it))) }
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
        // not read as some new jargon to a child - the name is the reason, and
        // saying which kind of thing it is makes that legible.
        return listOfNotNull(reasonPhrase(reason), until).joinToString(" ")
    }

    /** The reason as its own phrase, shared by the blocked rows and the why-dialog. */
    private fun reasonPhrase(reason: Reason): String = when (reason) {
        is Reason.ManuallyBlocked -> getString(R.string.reason_blocked)
        is Reason.UnmanagedBrowser -> getString(R.string.reason_unmanaged_browser)
        is Reason.DeviceBudgetExhausted -> getString(R.string.reason_daily_limit)
        is Reason.AppBudgetExhausted -> getString(R.string.reason_app_limit)
        is Reason.CategoryBudgetExhausted ->
            getString(R.string.reason_category_limit, reason.categoryName)
        is Reason.ContextRuleActive ->
            getString(R.string.reason_rule_active, reason.ruleName)
    }

    private fun row(
        pkg: PackageName,
        trailing: String,
        trailingColor: Int? = null,
        uninstallable: Boolean = false,
        onWhy: (() -> Unit)? = null,
        bodyLines: List<String> = emptyList(),
    ): View {
        val app = installedApps
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Top *and* bottom padding doubled the gap between consecutive
            // rows - in the blocked list especially, where every row carries a
            // two-line body, the list read as one app every inch. One bottom
            // margin gives the separation without the stacking.
            setPadding(0, 0, 0, dimen(R.dimen.space_xxs))

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
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                    addView(
                        TextView(context).apply {
                            text = app.labelFor(pkg)
                            styleAs(R.style.TextAppearance_Dementor_Body)
                        },
                    )
                    bodyLines.forEach { body ->
                        addView(
                            TextView(context).apply {
                                text = body
                                styleAs(R.style.TextAppearance_Dementor_Caption)
                                setTextColor(ContextCompat.getColor(context, R.color.state_critical))
                                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                                    topMargin = dimen(R.dimen.space_hairline)
                                }
                            },
                        )
                    }
                },
            )
            if (trailing.isNotEmpty()) {
                addView(
                    TextView(context).apply {
                        text = trailing
                        styleAs(R.style.TextAppearance_Dementor_Body)
                        trailingColor?.let { setTextColor(ContextCompat.getColor(context, it)) }
                    },
                )
            }
            if (uninstallable) {
                addView(
                    ImageView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            dimen(R.dimen.row_icon_small),
                            dimen(R.dimen.row_icon_small),
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
     * Sends [pkg] to Android's own uninstall-confirmation dialog - the same
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

    /**
     * The full answer for one blocked app, in a dialog the row can open.
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

    private fun line(text: String) = TextView(this).apply {
        this.text = text
        styleAs(R.style.TextAppearance_Dementor_Body)
        setPadding(0, dimen(R.dimen.space_xxs), 0, dimen(R.dimen.space_xxs))
    }

    companion object {
        /** Not a package: the pinned ask's row carries no app of its own. */
        private const val ASK_MORE_TIME_TAG = "ask-more-time"

        /** The app a block notification names, so the list can explain it. */
        const val EXTRA_BLOCKED_PACKAGE = "blockedPackage"

        fun intent(context: Context, pkg: PackageName? = null): Intent =
            Intent(context, BlockedAppsActivity::class.java)
                .apply { pkg?.let { putExtra(EXTRA_BLOCKED_PACKAGE, it.value) } }
    }
}
