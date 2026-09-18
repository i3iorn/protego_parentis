package dev.schrammel.protego_parentis.sync

import android.content.Context
import dev.schrammel.protego_parentis.audit.Audit
import dev.schrammel.protego_parentis.enforcement.RequestNotifier
import dev.schrammel.protego_parentis.model.AuditAction
import dev.schrammel.protego_parentis.model.AuditActor
import dev.schrammel.protego_parentis.model.RequestId
import dev.schrammel.protego_parentis.model.RequestState
import dev.schrammel.protego_parentis.policy.request.RequestRules
import dev.schrammel.protego_parentis.rules.RequestCoordinator
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_EXPIRES_AT
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_MINUTES
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_NOTE
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_REASON
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_REQUEST_ID
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_RULE_NAME
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_STATE
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_TARGET
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_TARGET_LABEL
import dev.schrammel.protego_parentis.sync.core.SyncMessage
import java.time.Instant

/**
 * Everything about a time request — raised, decided, auto-approved, or
 * settled — on whichever side of the family reads it. [applyDecision] is the
 * child hearing its own request answered; [rememberRequest]/[settleRequest]/
 * [settleAutoApproved] are a parent hearing about one raised, closed by
 * another parent, or closed by a standing rule; [applyAnsweredRequests] is
 * the child catching up on an answer that arrived as replicated ops instead
 * of the legacy message it also still understands. All five share the same
 * two collaborators — [RequestCoordinator] and [InboxStore] — which is the
 * grouping this class exists to keep visible.
 */
internal class RequestSyncHandler(
    context: Context,
    private val requests: RequestCoordinator,
    private val inbox: InboxStore,
    private val notifier: RequestNotifier,
) {
    private val appContext = context.applicationContext

    fun applyDecision(message: SyncMessage): Boolean {
        val id = RequestId(message.field(FIELD_REQUEST_ID) ?: return false)
        val note = message.field(FIELD_NOTE)

        return when (message.field(FIELD_STATE)) {
            RequestState.APPROVED.name -> requests.approve(id, note)
            RequestState.DENIED.name -> requests.deny(id, note)
            else -> false
        }
    }

    fun settleAutoApproved(message: SyncMessage): Boolean {
        val id = message.field(FIELD_REQUEST_ID) ?: return false
        val rule = message.field(FIELD_RULE_NAME)?.clip()

        // A request answered by a standing rule never sat pending, so no parent
        // phone has heard of it. Settling it works when this phone did see the
        // request; when it did not, there is nothing to attach the answer to and
        // the auto-approval is simply noted in the history instead. The old code
        // invented a synthetic request here, which under replication would be a
        // second copy of one the child had already published.
        if (!inbox.settle(id, RequestState.APPROVED, rule, decidedBy = AUTOMATIC)) {
            Audit.record(
                context = appContext,
                actor = AuditActor.PARENT,
                action = AuditAction.REQUEST_APPROVED,
                subject = message.field(FIELD_TARGET_LABEL)?.clip(),
                after = rule,
            )
        }
        return true
    }

    /**
     * A request from a phone still on the older protocol.
     *
     * Folded into the replica rather than a separate inbox, so a part-updated
     * family sees one list. A newer child publishes the same request as ops and
     * this path is never reached for it.
     */
    fun rememberRequest(message: SyncMessage): Boolean {
        val minutes = message.longField(FIELD_MINUTES)?.toInt()?.takeIf { it > 0 } ?: return false
        val id = message.field(FIELD_REQUEST_ID) ?: return false
        if (inbox.all().any { it.id == id }) return false

        val expiresAt = message.longField(FIELD_EXPIRES_AT)
            ?.let(Instant::ofEpochMilli)
            ?: message.sentAt.plusMillis(RequestRules.DEFAULT_LIFETIME.inWholeMilliseconds)

        RequestReplication(appContext).raise(
            minutes = minutes,
            reason = message.field(FIELD_REASON).orEmpty().clip(),
            target = message.field(FIELD_TARGET).orEmpty(),
            targetLabel = message.field(FIELD_TARGET_LABEL).orEmpty().clip(),
            expiresAt = expiresAt,
        ) ?: return false

        notifier.notifyIncomingRequest(
            IncomingRequest(
                id = id,
                label = message.field(FIELD_TARGET_LABEL).orEmpty().clip(),
                minutes = minutes,
                reason = message.field(FIELD_REASON).orEmpty().clip(),
                raisedAt = message.sentAt,
                expiresAt = expiresAt,
                state = RequestState.PENDING,
            ),
        )
        return true
    }

    // Another parent answered first. Recording that here is what stops two
    // parents answering the same request twice.
    fun settleRequest(message: SyncMessage): Boolean {
        val id = message.field(FIELD_REQUEST_ID) ?: return false
        val state = RequestState.entries.firstOrNull { it.name == message.field(FIELD_STATE) }
            ?: return false
        if (!state.isDecided) return false

        return inbox.settle(id, state, message.field(FIELD_NOTE), decidedBy = message.sender)
    }

    /**
     * Grants time for any of this phone's requests that a parent has answered.
     *
     * Matched by the local id the request was raised with, because the replica
     * knows it by an id of its own. Answers are applied through the ordinary
     * [RequestCoordinator], which is what actually issues the grant and refuses
     * a second answer — so an approval seen again on the next poll is harmless.
     */
    fun applyAnsweredRequests() {
        RequestReplication(appContext).all().forEach { replicated ->
            val local = replicated.localId?.let(::RequestId) ?: return@forEach
            when (replicated.state) {
                RequestState.APPROVED -> requests.approve(local, replicated.note)
                RequestState.DENIED -> requests.deny(local, replicated.note)
                else -> Unit
            }
        }
    }

    private companion object {
        /** Recorded as the decider when a standing rule answered, not a person. */
        const val AUTOMATIC = "rule"
    }
}
