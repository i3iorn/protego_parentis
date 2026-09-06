package dev.schrammel.protego_parentis.audit

import android.content.Context
import androidx.core.content.edit
import dev.schrammel.protego_parentis.model.AuditAction
import dev.schrammel.protego_parentis.model.AuditActor
import dev.schrammel.protego_parentis.model.AuditChain
import dev.schrammel.protego_parentis.model.AuditEntry
import dev.schrammel.protego_parentis.sync.AuditReplication
import dev.schrammel.protego_parentis.model.ChainCheck
import dev.schrammel.protego_parentis.sync.LocalDeviceId
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

/**
 * The family's shared history (TR-3).
 *
 * Every rule change, every request and every answer, with who made it and when.
 * Both sides read the identical list from their own screen — there is no parent
 * view and child view, there is one log.
 *
 * Append-only by construction: nothing here updates or deletes an entry, and the
 * only way a row leaves is by ageing out past the cap in [AuditLogFormat].
 *
 * A static object taking a [Context], matching `Diagnostics`, because recording
 * happens from twenty scattered call sites and threading a store through all of
 * them would guarantee the one that gets forgotten is the interesting one.
 */
object Audit {

    /**
     * Serialises the read-modify-write below.
     *
     * Entries are recorded from the enforcement worker, the sync executor and the
     * UI thread. Appending is read-then-write, so without this two entries written
     * at the same moment lose one of themselves — and a history that quietly drops
     * rows is worse than no history, because it is still believed.
     */
    private val lock = Any()

    /**
     * Records an action, unless nothing actually changed.
     *
     * The no-op guard is not a nicety: sliders and switches fire on every touch,
     * and a history where nine rows in ten say "changed from 45 to 45" is one
     * nobody will scroll.
     */
    fun record(
        context: Context,
        actor: AuditActor,
        action: AuditAction,
        subject: String? = null,
        before: String? = null,
        after: String? = null,
        at: Instant = Instant.now(),
    ) {
        if (before != null && before == after) return

        val entry = AuditEntry(
            id = UUID.randomUUID().toString(),
            at = at,
            actor = actor,
            action = action,
            subject = subject,
            before = before,
            after = after,
        )
        val linked = synchronized(lock) {
            val prefs = prefs(context)
            val appended = AuditLogFormat.append(
                raw = prefs.getString(KEY_LOG, null),
                anchor = prefs.getString(KEY_ANCHOR, null) ?: AuditChain.GENESIS,
                entry = entry,
            )
            // commit, not apply: a crash between recording an action and the write
            // landing would leave the history disagreeing with what the app did.
            prefs.edit(commit = true) {
                putString(KEY_LOG, appended.log)
                putString(KEY_ANCHOR, appended.anchor)
                // Only ever incremented. Sent to the parents so a history that has
                // been rebuilt rather than appended to shows up as a count that
                // went backwards — which is the one thing a local chain cannot
                // catch on its own.
                putLong(KEY_TOTAL, prefs.getLong(KEY_TOTAL, 0L) + 1L)
            }
            appended.entry
        }

        // Recorded twice, deliberately, and the two copies do different jobs.
        //
        // The local log above is a hash chain: it proves *this phone's* history
        // has not been edited underneath the app. That chain is linear by
        // nature, and entries from three other devices arriving out of order
        // cannot be threaded onto it without destroying the property.
        //
        // So the replica below carries the same entry to the rest of the
        // family, unchained, and [entries] shows the union. The chain answers
        // "has this phone's log been tampered with"; replication answers "does
        // the family see the same history". Both were promised, and neither
        // substitutes for the other.
        AuditReplication(context).record(
            actor = actor.name,
            action = action.name,
            subject = subject,
            before = before,
            after = after,
            previousHash = linked.previousHash,
            hash = linked.hash,
        )
    }

    /**
     * Newest first, the way the screen reads it.
     *
     * This phone's own chained entries, plus what every other device in the
     * family recorded. Nothing is de-duplicated because nothing overlaps: the
     * local log holds only what this phone wrote, and the replica is filtered to
     * what it did not.
     */
    fun entries(context: Context): List<AuditEntry> {
        val own = AuditLogFormat.decodeAll(prefs(context).getString(KEY_LOG, null))
        val self = LocalDeviceId.current()

        val fromFamily = AuditReplication(context).entries()
            .filter { self == null || it.by != self }
            .mapNotNull { entry ->
                AuditEntry(
                    id = entry.id,
                    at = entry.at,
                    actor = AuditActor.entries.firstOrNull { it.name == entry.actor }
                        ?: return@mapNotNull null,
                    action = AuditAction.entries.firstOrNull { it.name == entry.action }
                        ?: return@mapNotNull null,
                    subject = entry.subject,
                    before = entry.before,
                    after = entry.after,
                )
            }

        return (own + fromFamily).sortedByDescending { it.at }
    }

    /**
     * Whether the stored history still adds up (TR-3).
     *
     * Each entry is chained to the one before it, so an edit made outside this app
     * — a text editor on a rooted phone, a restored backup, a corrupted write —
     * breaks every link after it. The screen shows the result, because a history
     * whose integrity is never checked is one nobody has any reason to believe.
     *
     * Each entry with a [AuditEntry.sig] (#84) is also checked against a fresh
     * [AuditSignature.sign] of its own hash. This is the one thing the plain
     * SHA-256 chain alone cannot catch: SHA-256 has no secret in it, so anyone
     * who can write to this preferences file can also recompute a chain that
     * verifies perfectly — a forged history and a real one look identical to
     * [AuditChain.verify] alone. Reproducing a *signature* needs the Keystore
     * to actually sign again, which needs this app's identity, not just file
     * access. An entry with no `sig` — recorded before this existed, or on a
     * device whose keystore refused — is skipped rather than treated as
     * broken; the plain hash chain still covers it exactly as before.
     *
     * What neither check establishes: the anchor is read from the same
     * preferences file as the entries, so anyone able to rewrite one can
     * rewrite the other and this still returns Intact — and an attacker who
     * can run *this app's own code* can still ask its own Keystore key to
     * sign a rewritten chain, the same limit [SecretVault] already lives with
     * for the family passphrase. The evidence that survives a deliberate
     * rewrite by someone who cannot run this app at all is the parent's, via
     * [witness] — see IntegrityRules.historyRewritten.
     */
    fun check(context: Context): ChainCheck {
        val prefs = prefs(context)
        val entries = AuditLogFormat.decodeAll(prefs.getString(KEY_LOG, null))
        val anchor = prefs.getString(KEY_ANCHOR, null) ?: AuditChain.GENESIS

        val chained = AuditChain.verify(entries, anchor)
        if (!chained.isIntact) return chained

        entries.forEachIndexed { index, entry ->
            val sig = entry.sig ?: return@forEachIndexed
            if (sig != AuditSignature.sign(entry.hash)) return ChainCheck.Broken(index)
        }
        return ChainCheck.Intact
    }

    /**
     * Whether every *other* device's own replicated chain still adds up (#84).
     *
     * [check] only ever proved this phone's own copy was self-consistent —
     * exactly the limit its own doc comment names. Each device now replicates
     * its entries' [AuditEntry.previousHash]/[AuditEntry.hash] alongside their
     * content, computed the same way [check] verifies them, so a receiving
     * phone can re-walk another device's chain instead of only displaying it.
     * A replica cannot repair that after the fact: it did not record the
     * entry, so it does not have the one previous hash that would make a
     * forged replacement link up.
     *
     * Grouped by [ReplicatedAuditEntry.by] and ordered by [ReplicatedAuditEntry.at]
     * — the order the recording device wrote them in, which its own chain
     * fields were computed against. Entries with no chain fields at all
     * (replicated before this existed) are skipped rather than counted as
     * broken; a family mid-upgrade is not a family under attack.
     */
    fun checkFamily(context: Context): ChainCheck {
        val self = LocalDeviceId.current()
        val byDevice = AuditReplication(context).entries()
            .filter { self == null || it.by != self }
            .groupBy { it.by }

        byDevice.values.forEach { deviceEntries ->
            val chained = deviceEntries
                .filter { it.hash != null && it.previousHash != null }
                .sortedBy { it.at }
                .mapNotNull { entry ->
                    val actor = AuditActor.entries.firstOrNull { it.name == entry.actor } ?: return@mapNotNull null
                    val action = AuditAction.entries.firstOrNull { it.name == entry.action } ?: return@mapNotNull null
                    AuditEntry(
                        id = entry.id,
                        at = entry.at,
                        actor = actor,
                        action = action,
                        subject = entry.subject,
                        before = entry.before,
                        after = entry.after,
                        previousHash = entry.previousHash ?: AuditChain.GENESIS,
                        hash = entry.hash.orEmpty(),
                    )
                }
            val result = AuditChain.verify(chained)
            if (!result.isIntact) return result
        }
        return ChainCheck.Intact
    }

    /**
     * The head of the chain and how many entries have ever been written.
     *
     * Travels in the heartbeat. A parent's copy of these two numbers lives
     * somewhere the child cannot rewrite, which is what makes trimming the log
     * distinguishable from it simply being full.
     */
    fun witness(context: Context): Pair<String, Long> {
        val prefs = prefs(context)
        val head = AuditLogFormat.decodeAll(prefs.getString(KEY_LOG, null))
            .lastOrNull()
            ?.hash
            ?: prefs.getString(KEY_ANCHOR, null)
            ?: AuditChain.GENESIS
        return head to prefs.getLong(KEY_TOTAL, 0L)
    }

    fun minutes(value: Int): String = value.toString()

    fun flag(value: Boolean): String = value.toString()

    fun timeRange(start: LocalTime, end: LocalTime): String = "$start-$end"

    private const val FILE_NAME = "audit"
    private const val KEY_LOG = "log"

    /**
     * The hash the chain continues from.
     *
     * Not always the genesis value: the log is capped, so once old entries age out
     * the chain resumes from the hash of the last one dropped. Keeping it is what
     * stops an ordinary trim looking exactly like a deleted first page.
     */
    private const val KEY_ANCHOR = "anchor"

    /** Entries ever written, as opposed to entries still stored. */
    private const val KEY_TOTAL = "total"

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
}
