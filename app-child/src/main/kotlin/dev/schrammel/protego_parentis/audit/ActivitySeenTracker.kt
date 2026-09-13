package dev.schrammel.protego_parentis.audit

import android.content.Context
import androidx.core.content.edit
import dev.schrammel.protego_parentis.model.AuditEntry
import java.time.Instant

/**
 * How much of the shared history this phone has not looked at yet.
 *
 * Purely local: what one phone has "seen" is not something the family needs
 * to agree on, and replicating it would mean two people reading the same
 * history at the same moment fight over whose "seen" wins. Each device keeps
 * its own watermark, the same way an email client's unread state is per
 * device rather than per mailbox.
 *
 * The watermark is a moment in time rather than a set of entry ids: [Audit]
 * already orders everything by [AuditEntry.at], so "since I last looked" is
 * one comparison per entry rather than a growing set this would otherwise
 * need to prune.
 */
class ActivitySeenTracker(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /**
     * Which of [entries] arrived after this phone last opened the history.
     *
     * The very first call a phone ever makes treats everything already there
     * as background rather than a backlog to announce — a family that has
     * used the app for months must not see "347 new" the moment this feature
     * ships. Only activity from here on counts.
     */
    fun unseen(entries: List<AuditEntry>): List<AuditEntry> {
        val seenAt = lastSeenAt()
        if (seenAt == null) {
            markSeen(entries)
            return emptyList()
        }
        return entries.filter { it.at.isAfter(seenAt) }
    }

    /** Call once the history has actually been shown, not merely counted. */
    fun markSeen(entries: List<AuditEntry>) {
        val newest = entries.maxOfOrNull { it.at } ?: return
        if (lastSeenAt()?.isAfter(newest) == true) return
        prefs.edit { putLong(KEY_SEEN_AT, newest.toEpochMilli()) }
    }

    private fun lastSeenAt(): Instant? =
        prefs.getLong(KEY_SEEN_AT, -1L).takeIf { it >= 0L }?.let(Instant::ofEpochMilli)

    private companion object {
        const val FILE_NAME = "activity_seen"
        const val KEY_SEEN_AT = "seen_at"
    }
}
