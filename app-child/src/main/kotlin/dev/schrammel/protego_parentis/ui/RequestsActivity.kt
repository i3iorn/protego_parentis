package dev.schrammel.protego_parentis.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import dev.schrammel.protego_parentis.AppRegistry
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.model.CategoryId
import dev.schrammel.protego_parentis.model.GrantTarget
import dev.schrammel.protego_parentis.model.Request
import dev.schrammel.protego_parentis.model.RequestState
import dev.schrammel.protego_parentis.policy.request.SubmitDecision
import dev.schrammel.protego_parentis.rules.LocalPolicyStore
import dev.schrammel.protego_parentis.rules.RequestCoordinator
import dev.schrammel.protego_parentis.rules.TargetLabels
import dev.schrammel.protego_parentis.sync.SyncGraph
import dev.schrammel.protego_parentis.usage.InstalledApps
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The child's side of the negotiation (RQ-1).
 *
 * Asking is deliberately cheap and answering is deliberately informative: the
 * reason field is required, because a bare "+15 minutes" gives a parent nothing
 * to decide on and turns the feature into a slot machine.
 */
class RequestsActivity : DementorActivity() {
    override val showsBackButton = true

    private lateinit var coordinator: RequestCoordinator
    private lateinit var policyStore: LocalPolicyStore
    private lateinit var installedApps: InstalledApps
    private lateinit var container: LinearLayout

    /** Given `R.id.reason_input` so rotation doesn't silently discard whatever was typed. */
    private lateinit var reasonInput: EditText
    private var target: GrantTarget = GrantTarget.Device

    /**
     * What [onSaveInstanceState] carried across the recreation. The automatic
     * view-state restore puts the typed reason on the *old* hierarchy, and
     * [render]'s rebuild in [onResume] would throw that away — so the text is
     * carried by hand and consumed by the first render after recreation.
     * After that, renders keep whatever is live in the field.
     */
    private var pendingRestore: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Publishing is fire-and-forget and off the main thread; an offline phone
        // simply keeps the request local until the next poll drains it.
        val sync = SyncGraph.get(this)
        policyStore = AppRegistry.childStores.policy
        installedApps = InstalledApps(this)
        coordinator = RequestCoordinator(
            context = this,
            // Raising a request tells every parent phone. The label goes with it
            // because a parent's phone has no app list to resolve a package name
            // against, and "com.instagram.android" is not a question anyone can
            // answer from a notification.
            onRaised = { request -> sync.publishRequestRaised(request, describe(request.target)) },
            // Without this the parents' inboxes keep showing a request nobody can
            // answer any more, until it quietly expires.
            onAutoApproved = { request, rule ->
                sync.publishAutoApproved(request, describe(request.target), rule)
            },
        )

        container = contentColumn()
        showColumn(container)

        savedInstanceState?.getString(KEY_REASON)?.let { pendingRestore = it }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::reasonInput.isInitialized) {
            outState.putString(KEY_REASON, reasonInput.text.toString())
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        // Rotation saves and restores the view hierarchy — including whatever
        // the reason field holds — but the onResume() that follows recreates
        // every view from scratch, and the saved text would die with the old
        // EditText. Carrying it across the rebuild keeps a half-written
        // request alive through both a rotation and a target re-pick.
        val reasonSoFar = pendingRestore
            ?: if (::reasonInput.isInitialized) reasonInput.text.toString() else ""
        pendingRestore = null
        container.removeAllViews()
        container.addView(screenTitle(getString(R.string.requests_title)))

        renderCompose()
        reasonInput.setText(reasonSoFar)
        renderHistory()
    }

    // ---------- asking ----------

    private fun renderCompose() {
        container.addView(heading(getString(R.string.requests_ask_for), R.style.TextAppearance_Dementor_Section))
        container.addView(targetPicker())

        reasonInput = EditText(this).apply {
            id = R.id.reason_input
            hint = getString(R.string.requests_reason_hint)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        container.addView(reasonInput)

        val amounts = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = dimen(R.dimen.space_md)
            }
        }
        coordinator.suggestedAmounts().forEach { amount ->
            amounts.addView(
                Button(this).apply {
                    text = getString(R.string.requests_ask_minutes, amount.inWholeMinutes)
                    layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                        marginEnd = dimen(R.dimen.space_xs)
                    }
                    setOnClickListener { submit(amount) }
                },
            )
        }
        container.addView(amounts)

        // The presets cover the common asks; this is for everything else. Not a
        // replacement for them — someone who already knows they want 15 minutes
        // should not have to drag a slider to say so.
        container.addView(
            actionButton().apply {
                setText(R.string.requests_ask_custom)
                setOnClickListener { askForCustomAmount() }
            },
        )
    }

    private fun askForCustomAmount() {
        sliderDialog(
            title = R.string.requests_custom_title,
            max = LimitSlider.MAX_STEPS,
            initialProgress = LimitSlider.progressFor(DEFAULT_CUSTOM_MINUTES),
            format = { formatLimit(LimitSlider.minutesFor(it)) },
        ) { progress ->
            val minutes = LimitSlider.minutesFor(progress)
            if (minutes <= 0) {
                toast(getString(R.string.requests_custom_zero))
                return@sliderDialog
            }
            submit(minutes.minutes)
        }
    }

    /**
     * Lets the child aim the request at the whole device or at one category.
     * Categories are the common case: "more games time" is a different ask from
     * "more phone time", and a parent answers them differently.
     */
    private fun targetPicker(): LinearLayout {
        val group = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val options = buildList {
            add(getString(R.string.requests_target_device) to GrantTarget.Device)
            policyStore.categories.sortedBy { it.name.lowercase() }.forEach { category ->
                add(category.name to GrantTarget.Category(CategoryId(category.id)))
            }
        }

        options.forEach { (label, option) ->
            val selected = option == target
            group.addView(
                TextView(this).apply {
                    text = if (selected) getString(R.string.requests_target_selected, label) else label
                    styleAs(R.style.TextAppearance_Dementor_ListItem)
                    setPadding(0, dimen(R.dimen.space_xs), 0, dimen(R.dimen.space_xs))
                    // The trailing checkmark alone is easy to miss scanning a
                    // longer list of categories quickly; bold and coloured makes
                    // the selected row not just readable but findable.
                    if (selected) {
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setTextColor(ContextCompat.getColor(context, R.color.brand))
                    }
                    setOnClickListener {
                        target = option
                        render()
                    }
                },
            )
        }
        return group
    }

    private fun submit(amount: Duration) {
        val reason = reasonInput.text.toString().trim()
        if (reason.isEmpty()) {
            toast(getString(R.string.requests_reason_required))
            return
        }

        when (val decision = coordinator.canSubmit(target)) {
            is SubmitDecision.AlreadyPending -> toast(getString(R.string.requests_already_pending))
            is SubmitDecision.TooManyDenials -> toast(
                resources.getQuantityString(
                    R.plurals.requests_too_many,
                    decision.deniedToday,
                    decision.deniedToday,
                ),
            )

            is SubmitDecision.Allowed -> {
                // The return value is checked rather than assumed. submit() has
                // its own refusals — asking can be switched off entirely, and the
                // rules are re-checked there — so a screen left open from before a
                // change can reach this line and get nothing. It used to say
                // "Sent" regardless, which is the worst possible answer: the one
                // that stops anybody asking again.
                val raised = coordinator.submit(target, amount, reason)
                if (raised == null) {
                    toast(getString(R.string.requests_not_sent))
                    return
                }
                reasonInput.setText("")
                toast(getString(R.string.requests_sent))
                render()
            }
        }
    }

    // ---------- history ----------

    private fun renderHistory() {
        container.addView(heading(getString(R.string.requests_history), R.style.TextAppearance_Dementor_Section))

        Background.load(this, { coordinator.all() }) { all ->
            if (all.isEmpty()) {
                container.addView(bodyText(getString(R.string.requests_none)))
            } else {
                addCollapsibleRows(container, all.map(::requestRow))
            }
        }
    }

    private fun requestRow(request: Request): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dimen(R.dimen.space_sm), 0, dimen(R.dimen.space_sm))

        addView(
            bodyText(
                getString(
                    R.string.requests_row_summary,
                    request.amount.inWholeMinutes,
                    describe(request.target),
                    formatTime(request.createdAt),
                ),
            ).styleAs(R.style.TextAppearance_Dementor_ListItem),
        )
        addView(bodyText(getString(R.string.requests_row_reason, request.reason)))
        addView(bodyText(statusLine(request)))
        // The same deadline the parent's own inbox already shows for this
        // request — without it, a child watching a pending row has no way to
        // tell "still has time" from "about to lapse," and the eventual
        // silent flip to "no answer in time" reads as if nobody looked.
        if (request.state == RequestState.PENDING) {
            addView(captionText(getString(R.string.inbox_expires_at, formatTime(request.expiresAt))))
        }

        if (request.state.isOpen) {
            addView(
                Button(context).apply {
                    setText(R.string.requests_withdraw)
                    setOnClickListener {
                        coordinator.withdraw(request.id)
                        render()
                    }
                },
            )
        }
    }

    private fun statusLine(request: Request): String {
        val note = request.decisionNote
        return when (request.state) {
            RequestState.PENDING -> getString(R.string.requests_state_pending)
            // Named rather than folded into "approved": a child should be able to
            // tell an answer their parent gave from one the phone gave itself.
            RequestState.APPROVED -> if (request.autoApprovedBy != null) {
                getString(R.string.requests_state_auto)
            } else {
                getString(R.string.requests_state_approved)
            } +
                (note?.let { " — $it" }.orEmpty())

            RequestState.DENIED -> getString(R.string.requests_state_denied) +
                (note?.let { " — $it" }.orEmpty())

            RequestState.EXPIRED -> getString(R.string.requests_state_expired)
            RequestState.WITHDRAWN -> getString(R.string.requests_state_withdrawn)
        }
    }

    private fun describe(target: GrantTarget): String =
        TargetLabels.describe(this, target)

    // ---------- view helpers ----------

    private fun heading(text: String, appearance: Int) = TextView(this).apply {
        this.text = text
        styleAs(appearance)
        setPadding(0, dimen(R.dimen.section_spacing), 0, dimen(R.dimen.space_xxs))
    }

    companion object {
        private const val DEFAULT_CUSTOM_MINUTES = 45
        private const val KEY_REASON = "requests_reason"

        fun intent(context: Context) = Intent(context, RequestsActivity::class.java)
    }
}
