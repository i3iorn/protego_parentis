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
import dev.schrammel.dementor_watch.model.RequestState
import dev.schrammel.dementor_watch.sync.IncomingRequest
import dev.schrammel.dementor_watch.sync.InboxStore
import dev.schrammel.dementor_watch.sync.SyncCoordinator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Answering requests from a parent's own phone.
 *
 * The counterpart of [RequestInboxActivity], which does the same job while
 * standing on the child's device. They are separate because the two situations
 * are genuinely different: that one holds the grant store and can apply the time
 * itself, this one can only say yes and let the child's phone do the applying.
 *
 * A decision here is published and then recorded locally as settled. If the
 * message never reaches the child the request simply expires unanswered, which is
 * the honest failure: better than a parent believing they granted time that the
 * child never received.
 */
class ParentInboxActivity : ParentRoleActivity() {

    private lateinit var sync: SyncCoordinator
    private lateinit var inbox: InboxStore
    private lateinit var column: LinearLayout

    private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern(TIME_PATTERN)

    override fun onParentPhoneReady(savedInstanceState: Bundle?) {
        sync = SyncCoordinator(this)
        inbox = InboxStore(this)

        column = contentColumn()
        showColumn(column)
    }

    override fun onParentPhoneResumed() {
        render()
        sync.pollNow {
            runOnUiThread {
                if (!isFinishing && !isDestroyed) render()
            }
        }
    }

    private fun render() {
        column.removeAllViews()
        column.addView(screenTitle(getString(R.string.inbox_title)))

        val now = Instant.now()
        val all = inbox.all(now)
        val pending = all.filter { it.isOpenAt(now) }

        if (pending.isEmpty()) {
            column.addView(bodyText(getString(R.string.inbox_empty)))
        } else {
            pending.forEach { column.addView(pendingCard(it)) }
        }

        val settled = all.filterNot { it.isOpenAt(now) }
        if (settled.isNotEmpty()) {
            column.addView(sectionHeader(getString(R.string.inbox_answered_header)))
            column.addView(group(settled.take(HISTORY_ROWS).map(::settledRow)))
        }
    }

    private fun pendingCard(request: IncomingRequest): LinearLayout = card {
        addView(
            TextView(this@ParentInboxActivity).apply {
                text = resources.getQuantityString(
                    R.plurals.inbox_asking_for,
                    request.minutes,
                    request.minutes,
                    label(request),
                )
                styleAs(R.style.TextAppearance_Dementor_Subtitle)
            },
        )
        addView(bodyText(getString(R.string.requests_row_reason, request.reason)))
        addView(
            captionText(
                getString(
                    R.string.inbox_expires_at,
                    timeFormat.format(request.expiresAt.atZone(ZoneId.systemDefault())),
                ),
            ),
        )
        addView(
            LinearLayout(this@ParentInboxActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dimen(R.dimen.space_sm) }

                addView(
                    Button(context).apply {
                        setText(R.string.inbox_approve)
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f,
                        ).apply { marginEnd = dimen(R.dimen.space_xs) }
                        setOnClickListener { decide(request, RequestState.APPROVED) }
                    },
                )
                addView(
                    Button(context).apply {
                        setText(R.string.inbox_deny)
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f,
                        )
                        setOnClickListener { decide(request, RequestState.DENIED) }
                    },
                )
            },
        )
    }

    private fun settledRow(request: IncomingRequest): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            setPadding(0, dimen(R.dimen.space_sm), 0, dimen(R.dimen.space_sm))

            addView(
                TextView(context).apply {
                    text = resources.getQuantityString(
                        R.plurals.inbox_asking_for,
                        request.minutes,
                        request.minutes,
                        label(request),
                    )
                    styleAs(R.style.TextAppearance_Dementor_ListItem)
                },
            )
            addView(
                TextView(context).apply {
                    text = describeState(request)
                    styleAs(R.style.TextAppearance_Dementor_Caption)
                },
            )
        }

    private fun describeState(request: IncomingRequest): String {
        val state = when (request.state) {
            RequestState.APPROVED -> getString(R.string.requests_state_approved)
            RequestState.DENIED -> getString(R.string.requests_state_denied)
            RequestState.EXPIRED -> getString(R.string.requests_state_expired)
            RequestState.WITHDRAWN -> getString(R.string.requests_state_withdrawn)
            RequestState.PENDING -> getString(R.string.requests_state_pending)
        }
        return request.decisionNote?.let { "$state — $it" } ?: state
    }

    /** The child's phone sends a label; older builds send none. */
    private fun label(request: IncomingRequest): String =
        request.label.ifBlank { getString(R.string.requests_target_device) }

    /**
     * Both answers offer a note, for the same reason the child's side does: a
     * denial with a reason is a conversation, a bare no is a wall.
     */
    private fun decide(request: IncomingRequest, state: RequestState) {
        val label = if (state == RequestState.APPROVED) {
            R.string.inbox_approve
        } else {
            R.string.inbox_deny
        }

        decisionDialog(label) { text ->
            val note = text.trim().takeIf(String::isNotEmpty)
            // No separate decision message. It carried the *replicated* id,
            // which the child looks up as one of its own request ids and never
            // matches — so it was a wasted message that did nothing, alongside
            // the write below that actually answers the request.
            inbox.settle(request.id, state, note, decidedBy = sync.settings().deviceName)
            Audit.record(
                context = this,
                actor = AuditActor.PARENT,
                action = if (state == RequestState.APPROVED) {
                    AuditAction.REQUEST_APPROVED
                } else {
                    AuditAction.REQUEST_DENIED
                },
                subject = label(request),
                after = Audit.minutes(request.minutes),
            )
            render()
        }
    }

    companion object {
        private const val TIME_PATTERN = "HH:mm"

        /** Enough to see this week's answers without becoming an archive. */
        private const val HISTORY_ROWS = 10

        fun intent(context: Context) = Intent(context, ParentInboxActivity::class.java)
    }
}
