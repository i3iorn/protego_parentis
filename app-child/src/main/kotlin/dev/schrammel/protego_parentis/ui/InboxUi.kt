package dev.schrammel.protego_parentis.ui

import android.app.Activity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.audit.Audit
import dev.schrammel.protego_parentis.model.AuditAction
import dev.schrammel.protego_parentis.model.AuditActor
import dev.schrammel.protego_parentis.model.RequestState
import dev.schrammel.protego_parentis.sync.InboxStore
import dev.schrammel.protego_parentis.sync.IncomingRequest
import dev.schrammel.protego_parentis.sync.SyncCoordinator
import java.time.Instant

/**
 * The pieces of answering a request that more than one screen needs.
 *
 * [ParentInboxActivity] is where requests are answered in full — reason, note,
 * history. The parent's dashboard carries a quick version of the same thing
 * (P2 of the 1.9.0 UX plan): the two requests closest to expiring, answerable
 * without opening anything. Sharing the settle-and-audit half here keeps the
 * two from drifting: the record of what was decided must be identical no
 * matter which screen it was decided on.
 */

/** The label a request is shown under; older builds send none. */
internal fun Activity.requestLabel(inbox: InboxStore, request: IncomingRequest): String =
    request.label.ifBlank { getString(R.string.requests_target_device) }

/** Records the decision and the audit entry. Call on the main thread. */
internal fun Activity.settleRequest(
    inbox: InboxStore,
    sync: SyncCoordinator,
    request: IncomingRequest,
    state: RequestState,
    note: String?,
) {
    inbox.settle(request.id, state, note, decidedBy = sync.settings().deviceName)
    Audit.record(
        context = this,
        actor = AuditActor.PARENT,
        action = if (state == RequestState.APPROVED) AuditAction.REQUEST_APPROVED else AuditAction.REQUEST_DENIED,
        subject = request.label.ifBlank { getString(R.string.requests_target_device) },
        after = Audit.minutes(request.minutes),
    )
}

/**
 * One pending request as a dashboard card: what was asked, when it lapses, and
 * the two answers. Approve is immediate — the common case is "yes, obviously" —
 * while deny is expected to carry a reason, so it opens the same note dialog
 * the inbox uses. An approval without a note is a yes; a denial without one is
 * a wall.
 */
internal fun Activity.inboxQuickCard(
    request: IncomingRequest,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
): LinearLayout = card {
    addView(
        TextView(this@inboxQuickCard).apply {
            text = resources.getQuantityString(
                R.plurals.inbox_asking_for,
                request.minutes,
                request.minutes,
                request.label.ifBlank { getString(R.string.requests_target_device) },
            )
            styleAs(R.style.TextAppearance_Dementor_Subtitle)
        },
    )
    addView(
        captionText(
            getString(
                R.string.inbox_expires_at,
                android.text.format.DateFormat.getTimeFormat(this@inboxQuickCard)
                    .format(request.expiresAt.toEpochMilli()),
            ),
        ).apply {
            // A request minutes from lapsing is worth more than a caption's
            // usual quiet: the colour is the urgency, the words are the time.
            setTextColor(
                ContextCompat.getColor(
                    this@inboxQuickCard,
                    if (request.expiresAt.isBefore(Instant.now().plusSeconds(EXPIRY_SOON_SECONDS))) {
                        R.color.state_warning
                    } else {
                        R.color.text_secondary
                    },
                ),
            )
        },
    )
    addView(
        twoButtonRow(
            R.string.inbox_approve,
            onApprove,
            R.string.inbox_deny,
            onDeny,
        ),
    )
}

/** A request expiring within this window is soon. */
private const val EXPIRY_SOON_SECONDS = 10L * 60
