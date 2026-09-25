package dev.schrammel.protego_parentis.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.schrammel.protego_parentis.AppRegistry
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.audit.Audit
import dev.schrammel.protego_parentis.model.AuditAction
import dev.schrammel.protego_parentis.model.AuditActor
import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.RuleEffect
import dev.schrammel.protego_parentis.model.RuleTarget
import dev.schrammel.protego_parentis.rules.AppReviewStore
import dev.schrammel.protego_parentis.rules.PolicySnapshot
import dev.schrammel.protego_parentis.sync.SyncGraph
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.ui.rules.RuleScope
import dev.schrammel.protego_parentis.ui.rules.RuleTargets
import dev.schrammel.protego_parentis.ui.rules.appCatalog
import dev.schrammel.protego_parentis.ui.rules.blockRuleIdFor
import dev.schrammel.protego_parentis.ui.rules.contextRuleRuleStore
import dev.schrammel.protego_parentis.ui.rules.delete
import dev.schrammel.protego_parentis.ui.rules.isAppBlocked
import dev.schrammel.protego_parentis.ui.rules.policyRuleStore
import dev.schrammel.protego_parentis.ui.rules.upsert
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
 * the network-blocked switch is an ordinary [PolicySnapshot] field, and the
 * blocked switch an ordinary [ContextRule] (a shortcut over the same rule
 * engine every other restriction goes through — see [AppLimitsActivity]'s own
 * doc comment for why this screen writes at [LocalPolicyStore.MIGRATED_APP_BLOCK_ID_PREFIX]'s
 * well-known per-app id rather than a field of its own) — it just never has a
 * "new" section to show.
 */
class AppControlActivity : ParentGatedActivity() {

    private lateinit var target: RuleScope
    private lateinit var column: LinearLayout
    private lateinit var rows: LinearLayout
    private var fresh: List<AppInfo> = emptyList()
    private var known: List<AppInfo> = emptyList()
    private var query = ""
    private val handler = Handler(Looper.getMainLooper())

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

    private fun render() {
        column.removeAllViews()
        column.addView(
            headingWithInfo(
                screenTitle(getString(R.string.apps_title)),
                R.string.apps_info_title,
                R.string.apps_info_body,
            ),
        )
        column.addView(captionText(getString(R.string.apps_hint)))
        column.addView(captionText(getString(R.string.apps_network_hint)))
        // A block set here is an ordinary ContextRule under the hood (see this
        // class's own doc comment), governed by the same precedence a category
        // rule or an Allow elsewhere can override.
        column.addView(
            actionButton().apply {
                setText(R.string.context_tutorial_open)
                setOnClickListener { startActivity(RulesTutorialActivity.intent(this@AppControlActivity)) }
            },
        )
        // Remote only: this device's own list is always current (LocalAppCatalog reads
        // the package manager directly), so there is nothing to ask a local target for.
        if (!target.isOwnDevice) {
            column.addView(
                actionButton().apply {
                    setText(R.string.apps_request_list_update)
                    setOnClickListener {
                        SyncGraph.get(this@AppControlActivity).publishAppInventoryRequest()
                        toast(getString(R.string.apps_request_list_update_sent))
                        pollForFreshInventory()
                    }
                },
            )
        }
        if (!canEdit) column.addView(readOnlyBanner { requestUnlock { onGatedContentVisible() } })
        // Outside the part that gets rebuilt on each keystroke, which would take
        // the cursor and the typed text with it. Prefilled because render() runs
        // again after a decision, and an empty box over a filtered list is worse
        // than either.
        column.addView(searchField(query) { query = it; renderRows() })
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(rows)
        reloadCatalog()
    }

    private fun reloadCatalog() {
        Background.load(this, {
            val apps = appCatalog(this, target).launchable()
            // The per-row state, read here where the rest of the disk work lives —
            // renderRows used to read it once per row instead (see [rowState]).
            val snapshot = policyRuleStore(this, target).read()
            val rules = contextRuleRuleStore(this, target).read()
            if (target.isOwnDevice) {
                val pending = AppRegistry.childStores.appReview.pending
                (apps.partition { it.pkg.value in pending }) to RowState(snapshot, rules)
            } else {
                (emptyList<AppInfo>() to apps) to RowState(snapshot, rules)
            }
        }) { (pair, state) ->
            val (pending, rest) = pair
            fresh = pending
            known = rest
            rowState = state
            renderRows()
        }
    }

    /**
     * Checks back for the answer to a list-update request, a few times over
     * the next minute — mirrors [AppPickerActivity.pollForFreshInventory],
     * which the same request button relies on for the same reason: the reply
     * arrives asynchronously over sync with nothing to call back into this
     * screen when it lands. Reloads just the catalog rather than the whole
     * screen, so a request made mid-search does not lose it.
     */
    private fun pollForFreshInventory(attempt: Int = 0) {
        if (attempt >= MAX_INVENTORY_POLL_ATTEMPTS) return
        handler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            reloadCatalog()
            pollForFreshInventory(attempt + 1)
        }, INVENTORY_POLL_INTERVAL_MILLIS)
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
            else -> rows.addView(group(knownMatches.map { app -> knownAppRow(app, rowState) }))
        }
    }

    /**
     * The rules state every row needs, read once per render rather than once
     * per row — `knownAppRow` used to decode the full rules JSON and policy
     * snapshot for every app in the list, O(rows) store reads on the main
     * thread per keystroke. Loaded with the catalog in [reloadCatalog]; a row
     * that finds nothing here falls back to reading (and repopulates it).
     */
    private var rowState: RowState = RowState(null, emptyList())

    private data class RowState(val snapshot: PolicySnapshot?, val rules: List<ContextRule>)

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
    private fun knownAppRow(app: AppInfo, state: RowState): LinearLayout =
        LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        setPadding(0, dimen(R.dimen.space_sm), 0, dimen(R.dimen.space_sm))

            // Normally handed in with the catalog load (see [rowState]); read here
            // only when a row renders before that load has landed.
            val snapshot = state.snapshot ?: policyRuleStore(this@AppControlActivity, target).read()
            val rules =
                state.rules.ifEmpty { contextRuleRuleStore(this@AppControlActivity, target).read() }

        val blockedNow = isBlocked(app.pkg, rules)
        val identityRow = identity(app, weighted = true, blocked = blockedNow)
        addView(identityRow)
        addView(
            CheckBox(context).apply {
                setText(R.string.apps_blocked)
                isChecked = blockedNow
                isEnabled = canEdit
                setOnCheckedChangeListener { _, blocked ->
                    setBlockedAlpha(identityRow, blocked)
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
                    rowState = RowState(null, emptyList())
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

    private fun identity(app: AppInfo, weighted: Boolean = false, blocked: Boolean = false): View =
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
                    // Decorative: the label right beside it already says the same
                    // thing.
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
            )
            addView(
                TextView(context).apply {
                    text = app.label
                    styleAs(R.style.TextAppearance_Dementor_ListItem)
                },
            )
            setBlockedAlpha(this, blocked)
        }

    /**
     * The glanceable cue [R.string.apps_hint] promises: a blocked app's icon (and
     * name, so this reads on greyscale displays too) dims rather than looking
     * identical to one that still runs.
     */
    private fun setBlockedAlpha(identityRow: View, blocked: Boolean) {
        val alpha = if (blocked) BLOCKED_ALPHA else 1f
        (identityRow as? ViewGroup)?.let { row ->
            for (i in 0 until row.childCount) row.getChildAt(i).alpha = alpha
        }
    }

    /**
     * Whether [pkg] has a block rule at its well-known id, among [rules].
     *
     * A shortcut over the same rule engine every other restriction goes
     * through: reads the [ContextRule] `LocalPolicyStore.migrateAppRulesToUnifiedRules`
     * converts an old-format block into, the same one this screen writes.
     * Takes the rule list rather than reading the store itself, and is
     * internal rather than private, so [AppControlActivityTest] can pin the
     * translation directly without a `target` to resolve a store against.
     * Delegates to [dev.schrammel.protego_parentis.ui.rules.isAppBlocked] —
     * shared with every other screen that edits a migrated rule by its
     * well-known id, so the id scheme has one place to change.
     */
    internal fun isBlocked(pkg: PackageName, rules: List<ContextRule>): Boolean = isAppBlocked(pkg, rules)

    internal fun blockRuleId(pkg: PackageName) = blockRuleIdFor(pkg)

    private fun decide(app: AppInfo, blocked: Boolean, wasNew: Boolean) {
        val store = contextRuleRuleStore(this, target)
        val id = blockRuleId(app.pkg)
        if (blocked) {
            store.upsert(
                ContextRule(
                    id = id,
                    name = getString(R.string.apps_title),
                    enabled = true,
                    condition = Condition.Always,
                    effect = RuleEffect.Block(hidden = false),
                    target = RuleTarget.Apps(setOf(app.pkg)),
                ),
            )
        } else {
            store.delete(id)
        }
        // The rules just changed; the cached row state is now stale. Drop it so
        // the next render reads fresh rather than painting the old answer.
        rowState = RowState(null, emptyList())
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
            AppRegistry.childStores.appReview.markReviewed(app.pkg.value)
            Audit.record(
                context = this,
                actor = AuditActor.PARENT,
                action = AuditAction.APP_REVIEWED,
                subject = app.label,
            )
            // Tell the parent phones, so the same app does not sit in their list
            // waiting for an answer that has already been given here.
            SyncGraph.get(this).publishAppDecision(app.pkg.value, blocked)
            render()
        } else {
            // Same acknowledgement ContextRuleEditActivity gives for the identical
            // situation: this writes to the same remotely-synced store.
            acknowledgeRuleWrite(contextRuleRuleStore(this, target))
        }
    }

    companion object {
        /** Dimmed rather than hidden — [R.string.apps_hint] promises the icon greys out, not that it vanishes. */
        private const val BLOCKED_ALPHA = 0.4f

        /** How often [pollForFreshInventory] checks back after a request. */
        private const val INVENTORY_POLL_INTERVAL_MILLIS = 5_000L

        /** ~a minute of checking — long enough for realtime sync's usual case, not forever. */
        private const val MAX_INVENTORY_POLL_ATTEMPTS = 12

        fun intent(context: Context, childId: DeviceId? = null) =
            RuleTargets.putExtra(Intent(context, AppControlActivity::class.java), childId)
    }
}
