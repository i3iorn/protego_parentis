package dev.schrammel.protego_parentis.sync.core

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

    /**
     * A ceiling on remembered ids, as a backstop rather than the mechanism.
     *
     * Counting was the mechanism, and it was the wrong one. The property wanted
     * is "no message is acted on twice while acting on it twice would still do
     * something", which is a statement about *time* — and a fixed count has no
     * relationship to the windows in [freshnessFor]. Two hundred ids sounds
     * ample until you notice that every admitted message consumes one: every
     * state op, every telemetry write, every digest, every line of a
     * conversation. A busy half hour evicted a command's id while that command
     * was still inside its own freshness window, and once evicted there was
     * nothing left to refuse a replay with.
     *
     * So eviction is by age now, and this only bounds what a flood can cost in
     * storage. Set far above what real traffic reaches inside the longest
     * window, so hitting it is an anomaly worth reporting rather than an
     * ordinary Tuesday — see [Remembered.droppedWhileFresh].
     */
    const val MAX_REMEMBERED = 5_000

    /** The window that applies to [type]. Commands are held to the short one. */
    fun freshnessFor(type: SyncMessageType): Duration = when (type) {
        SyncMessageType.CHAT_MESSAGE -> CONVERSATION_WINDOW
        else -> FRESHNESS_WINDOW
    }

    fun admit(message: SyncMessage, now: Instant, seen: Collection<SeenMessage>): Admission {
        if (seen.any { it.id == message.id }) return Admission.Reject(RejectReason.ALREADY_SEEN)

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
     * The seen-set after accepting [message], with anything too old to matter
     * dropped.
     *
     * An id is worth keeping exactly as long as the message it names could still
     * be admitted. Past that, [admit] would refuse a replay on freshness alone
     * and the id is dead weight. Held against the longest window rather than the
     * message's own, because the set does not record which type each id came
     * from and the cost of a slightly generous horizon is a few more rows.
     */
    fun remember(message: SyncMessage, now: Instant, seen: List<SeenMessage>): Remembered {
        val kept = (seen.filterNot { it.id == message.id } + SeenMessage(message.id, message.sentAt))
            .filter { !isPastKeeping(it, now) }

        // Only ever reached under a flood: real traffic does not put thousands of
        // messages inside one window. Whatever is dropped here is still fresh by
        // definition — everything stale went in the filter above — so the count
        // is the signal that replay protection was degraded, and by how much.
        val trimmed = kept.takeLast(MAX_REMEMBERED)
        return Remembered(seen = trimmed, droppedWhileFresh = kept.size - trimmed.size)
    }

    /** Whether [entry] names a message that could no longer be admitted anyway. */
    private fun isPastKeeping(entry: SeenMessage, now: Instant): Boolean {
        val age = now.toEpochMilli() - entry.sentAt.toEpochMilli()
        return age > LONGEST_WINDOW.inWholeMilliseconds
    }

    /** The most generous window any message type is held to. */
    private val LONGEST_WINDOW: Duration = CONVERSATION_WINDOW
}

/** An id worth refusing a second time, and when the message claimed to be sent. */
data class SeenMessage(val id: String, val sentAt: Instant)

/**
 * The seen-set after remembering one more, and what it cost.
 *
 * [droppedWhileFresh] is zero in every ordinary case. Anything else means the
 * ceiling was reached and ids were discarded while the messages they name could
 * still be replayed — the moment replay protection weakens, which used to happen
 * silently and by design.
 */
data class Remembered(val seen: List<SeenMessage>, val droppedWhileFresh: Int)
