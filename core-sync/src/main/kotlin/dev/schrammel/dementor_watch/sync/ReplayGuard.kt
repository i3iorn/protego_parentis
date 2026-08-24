package dev.schrammel.dementor_watch.sync

import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** Why a message that decrypted correctly is still not acted on. */
enum class RejectReason {
    /** Older than the accepted window. Almost certainly a captured message replayed. */
    TOO_OLD,

    /** Dated in the future beyond tolerance; a clock is wrong, or someone is trying it on. */
    TOO_NEW,

    /** This exact message has already been acted on. */
    ALREADY_SEEN,
}

sealed interface Admission {
    data object Accept : Admission
    data class Reject(val reason: RejectReason) : Admission

    val isAccepted: Boolean get() = this is Accept
}

/**
 * Refuses messages that are authentic but should still not be acted on.
 *
 * Encryption proves a message came from someone holding the key. It says nothing
 * about *when*. On a public topic an attacker can copy yesterday's genuine
 * "grant 30 minutes" and publish it again tonight — perfectly authentic, entirely
 * unwanted. Freshness plus a seen-set closes that.
 *
 * Pure and caller-driven: the set of seen ids is passed in and the updated set
 * returned, so persistence is the caller's business and this stays testable.
 */
object ReplayGuard {

    /**
     * How old a message may be and still be acted on.
     *
     * Generous enough to survive a phone that was offline for a while and is
     * catching up, short enough that a captured message is worthless by evening.
     */
    val FRESHNESS_WINDOW: Duration = 30.minutes

    /**
     * Tolerance for a sender whose clock runs fast.
     *
     * Without it, a parent's phone a minute ahead would have every message
     * rejected by the child's.
     */
    val CLOCK_SKEW_ALLOWANCE: Duration = 5.minutes

    /**
     * How old a *message* may be, as opposed to an instruction.
     *
     * The freshness window exists because replaying a captured message makes
     * something happen: time is granted, a phone locks, a position is asked for.
     * A chat message does none of that. Its entire effect is a row in a list, and
     * the store that holds it already refuses an id it has seen, so replaying one
     * achieves precisely nothing.
     *
     * Holding conversation to the command window would mean something worse than a
     * weak defence — it would mean a parent whose phone spent the afternoon in doze
     * opens the app to a conversation with holes in it, and never finds out that
     * anything was missing. Silent loss is the wrong failure for the one feature
     * here that is two people talking.
     *
     * A day, because that is the outer edge of what a relay keeps anyway.
     */
    val CONVERSATION_WINDOW: Duration = 24.hours

    /** How many recent ids to remember. Bounded so the store cannot grow forever. */
    const val REMEMBERED_IDS = 200

    /** The window that applies to [type]. Commands are held to the short one. */
    fun freshnessFor(type: SyncMessageType): Duration = when (type) {
        SyncMessageType.CHAT_MESSAGE -> CONVERSATION_WINDOW
        else -> FRESHNESS_WINDOW
    }

    fun admit(message: SyncMessage, now: Instant, seenIds: Collection<String>): Admission {
        if (message.id in seenIds) return Admission.Reject(RejectReason.ALREADY_SEEN)

        val age = now.toEpochMilli() - message.sentAt.toEpochMilli()
        if (age > freshnessFor(message.type).inWholeMilliseconds) {
            return Admission.Reject(RejectReason.TOO_OLD)
        }
        if (-age > CLOCK_SKEW_ALLOWANCE.inWholeMilliseconds) {
            return Admission.Reject(RejectReason.TOO_NEW)
        }
        return Admission.Accept
    }

    /**
     * The seen-set after accepting [id], oldest entries dropped once it is full.
     *
     * Newest last, so trimming from the front removes the ids least likely to be
     * replayed at us again.
     */
    fun remember(seenIds: List<String>, id: String): List<String> =
        (seenIds - id + id).takeLast(REMEMBERED_IDS)
}
