package dev.schrammel.protego_parentis.model

import java.time.Instant
import kotlin.time.Duration

/** Stable identifier for a request. */
@JvmInline
value class RequestId(val value: String) {
    override fun toString(): String = value
}

/**
 * Where a request can end up.
 *
 * Terminal states are final: a decided request is never reopened, because the
 * shared audit log has to be able to say what was asked and what was answered
 * without the answer changing afterwards. Asking again means a new request.
 */
enum class RequestState {
    PENDING,
    APPROVED,
    DENIED,

    /** Nobody answered in time. Distinct from a denial — no one said no. */
    EXPIRED,

    /** The child changed their mind before it was answered. */
    WITHDRAWN,
    ;

    val isOpen: Boolean get() = this == PENDING
    val isDecided: Boolean get() = this == APPROVED || this == DENIED
}

/**
 * A child's ask for an exception, and its answer.
 *
 * The reason field is not decoration. A request with a reason attached is a
 * conversation; a bare "+15 minutes" button is a slot machine, and a parent
 * answering the second one has nothing to go on.
 *
 * [target] is a [GrantTarget] so that approving a request maps directly onto the
 * grant machinery that already exists — the whole device, one app, or one category.
 */
data class Request(
    val id: RequestId,
    val target: GrantTarget,
    val amount: Duration,
    val reason: String,
    val state: RequestState,
    val createdAt: Instant,
    val expiresAt: Instant,
    val decidedAt: Instant? = null,
    val decidedBy: String? = null,
    val decisionNote: String? = null,
    /**
     * The standing rule that answered this, or null if a person did.
     *
     * Stored on the request because that is the durable record: the grant it
     * produced expires tonight and the shared history is capped, but a request
     * lives as long as the family does.
     */
    val autoApprovedBy: String? = null,
) {
    init {
        require(autoApprovedBy == null || state == RequestState.APPROVED) {
            "only an approved request can name the rule that approved it"
        }
        require(amount.isPositive()) { "a request must ask for a positive amount of time" }
        require(expiresAt.isAfter(createdAt)) { "a request cannot expire before it is made" }
        // Every settled state carries its timestamp, including expiry and
        // withdrawal, so the shared history can be read as a sequence of events.
        require(state.isOpen || decidedAt != null) {
            "a settled request must record when it was settled"
        }
    }

    fun hasExpired(now: Instant): Boolean = state.isOpen && !now.isBefore(expiresAt)
}
