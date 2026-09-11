package dev.schrammel.protego_parentis.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.audit.Audit
import dev.schrammel.protego_parentis.model.AuditAction
import dev.schrammel.protego_parentis.model.AuditActor
import dev.schrammel.protego_parentis.rules.AppReviewStore
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.sync.SyncCoordinator
import dev.schrammel.protego_parentis.ui.rules.appCatalog
import dev.schrammel.protego_parentis.ui.rules.policyRuleStore
import dev.schrammel.protego_parentis.usage.AppInfo

/**
 * Which apps are allowed on [target]'s phone, and what is new (AC-2, AC-3),
 * on whichever kind of phone edits it (#43).
 *
 * One screen rather than two, because "review the app that just appeared" and
 * "block an app" are the same decision reached from different directions. A
 * separate review screen would leave a family able to block an app only during
 * the short window in which it counted as new.
 *
 * New apps sit at the top with an explicit Allow and Block, and stay there until
 * one of the two is pressed. Opening the screen is not an answer: a glance that
 * gets interrupted must not silently count as approval.
 *
 * The "new arrivals" section only ever has anything in it on the managed phone
 * itself: [AppReviewStore]'s pending list is local bookkeeping tied to the
 * install broadcast on that one device, nothing a parent's phone has a
 * replicated copy of. Editing a remote child's rules from here still works —
 * the blocked/network-blocked switches are ordinary [PolicySnapshot] fields —
 * it just never has a "new" section to show.
 */
class AppControlActivity : ParentGatedActivity() {

    private lateinit var target: RuleTarget
    private lateinit var column: LinearLayout
    private lateinit var rows: LinearLayout
    private var fresh: List<AppInfo> = emptyList()
    private var known: List<AppInfo> = emptyList()
    private var query = ""

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
        column.addView(screenTitle(getString(R.string.apps_title)))
        column.addView(captionText(getString(R.string.apps_hint)))
        column.addView(captionText(getString(R.string.apps_network_hint)))
        if (!canEdit) column.addView(readOnlyBanner { requestUnlock { onGatedContentVisible() } })
        // Outside the part that gets rebuilt on each keystroke, which would take
        // the cursor and the typed text with it. Prefilled because render() runs
        // again after a decision, and an empty box over a filtered list is worse
        // than either.
        column.addView(searchField(query) { query = it; renderRows() })
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(rows)

        Background.load(this, {
            val apps = appCatalog(this, target).launchable()
            if (target.isOwnDevice) {
                val pending = AppReviewStore(this).pending
                apps.partition { it.pkg.value in pending }
            } else {
                emptyList<AppInfo>() to apps
            }
        }) { (pending, rest) ->
            fresh = pending
            known = rest
            renderRows()
        }
    }

    private fun renderRows() {
        rows.removeAllViews()

        // The search covers both sections. A new arrival is still an app somebody
        // may be looking for by name, and hiding it from the filter would make
        // the one section that needs an answer the one section search cannot find.
        val newMatches = AppSearch.filter(fresh, query, { it.label }, { it.pkg.value })
        val knownMatches = AppSearch.filter(known, query, { it.label }, { it.pkg.value })

        if (newMatches.isNotEmpty()) {
            rows.addView(sectionHeader(getString(R.string.apps_new_header)))
            rows.addView(captionText(getString(R.string.apps_new_hint)))
            rows.addView(group(newMatches.map(::newAppRow)))
        }

        rows.addView(sectionHeader(getString(R.string.apps_all_header)))
        when {
            // Genuinely no apps to show is a different thing from a search that
            // matched nothing, and needs a different answer from the reader.
            known.isEmpty() -> rows.addView(bodyText(getString(R.string.apps_none)))
            knownMatches.isEmpty() -> rows.addView(noSearchResults())
            else -> rows.addView(group(knownMatches.map(::knownAppRow)))
        }
    }

    /** A new arrival: name, icon, and the two answers. Only ever shown on the managed phone itself. */
    private fun newAppRow(app: AppInfo): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        setPadding(0, dimen(R.dimen.space_sm), 0, dimen(R.dimen.space_sm))

        addView(identity(app))
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    topMargin = dimen(R.dimen.space_xs)
                }
                addView(
                    Button(context).apply {
                        setText(R.string.apps_allow)
                        layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                            marginEnd = dimen(R.dimen.space_xs)
                        }
                        setOnClickListener { decide(app, blocked = false, wasNew = true) }
                    },
                )
                addView(
                    Button(context).apply {
                        setText(R.string.apps_block)
                        layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                        setOnClickListener { decide(app, blocked = true, wasNew = true) }
                    },
                )
            },
        )
    }

    /**
     * An app the family has already seen, with a switch meaning "not on this phone".
     *
     * A checkbox rather than a third state for hiding: making an icon disappear is
     * the harsher and rarer option, and offering it on the same row invites a tap
     * from someone who has not read the difference.
     */
    private fun knownAppRow(app: AppInfo): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        setPadding(0, dimen(R.dimen.space_sm), 0, dimen(R.dimen.space_sm))

        val snapshot = policyRuleStore(this@AppControlActivity, target).read()

        addView(identity(app, weighted = true))
        addView(
            CheckBox(context).apply {
                setText(R.string.apps_blocked)
                isChecked = app.pkg.value in snapshot?.blockedPackages.orEmpty()
                isEnabled = canEdit
                setOnCheckedChangeListener { _, blocked ->
                    decide(app, blocked, wasNew = false)
                }
            },
        )
        addView(
            CheckBox(context).apply {
                setText(R.string.apps_network_blocked)
                isChecked = app.pkg.value in snapshot?.networkBlockedPackages.orEmpty()
                isEnabled = canEdit
                setOnCheckedChangeListener { _, offline ->
                    val store = policyRuleStore(this@AppControlActivity, target)
                    val current = store.read() ?: return@setOnCheckedChangeListener
                    val updated = if (offline) {
                        current.networkBlockedPackages + app.pkg.value
                    } else {
                        current.networkBlockedPackages - app.pkg.value
                    }
                    store.write(current.copy(networkBlockedPackages = updated))
                    Audit.record(
                        context = this@AppControlActivity,
                        actor = AuditActor.PARENT,
                        action = AuditAction.APP_NETWORK_CHANGED,
                        subject = app.label,
                        after = Audit.flag(offline),
                    )
                }
            },
        )
    }

    private fun identity(app: AppInfo, weighted: Boolean = false): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = if (weighted) {
                LinearLayout.LayoutParams(0, WRAP, 1f)
            } else {
                LinearLayout.LayoutParams(MATCH, WRAP)
            }

            addView(
                ImageView(context).apply {
                    setImageDrawable(app.icon)
                    layoutParams = LinearLayout.LayoutParams(
                        dimen(R.dimen.row_icon),
                        dimen(R.dimen.row_icon),
                    ).apply { marginEnd = dimen(R.dimen.space_md) }
                },
            )
            addView(
                TextView(context).apply {
                    text = app.label
                    styleAs(R.style.TextAppearance_Dementor_ListItem)
                },
            )
        }

    private fun decide(app: AppInfo, blocked: Boolean, wasNew: Boolean) {
        val store = policyRuleStore(this, target)
        val snapshot = store.read() ?: return
        val updated = if (blocked) snapshot.blockedPackages + app.pkg.value else snapshot.blockedPackages - app.pkg.value
        store.write(snapshot.copy(blockedPackages = updated))
        Audit.record(
            context = this,
            actor = AuditActor.PARENT,
            action = AuditAction.APP_BLOCK_CHANGED,
            subject = app.label,
            after = Audit.flag(blocked),
        )

        if (wasNew) {
            // Only reachable when target.isOwnDevice — see this class's own doc
            // comment — so review/publishAppDecision are always this device's own.
            AppReviewStore(this).markReviewed(app.pkg.value)
            Audit.record(
                context = this,
                actor = AuditActor.PARENT,
                action = AuditAction.APP_REVIEWED,
                subject = app.label,
            )
            // Tell the parent phones, so the same app does not sit in their list
            // waiting for an answer that has already been given here.
            SyncCoordinator(this).publishAppDecision(app.pkg.value, blocked)
            render()
        }
    }

    companion object {
        fun intent(context: Context, childId: DeviceId? = null) =
            RuleTargets.putExtra(Intent(context, AppControlActivity::class.java), childId)
    }
}
