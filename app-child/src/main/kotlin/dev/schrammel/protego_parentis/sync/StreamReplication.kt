package dev.schrammel.protego_parentis.sync

import android.content.Context
import dev.schrammel.protego_parentis.security.RoleStore
import dev.schrammel.protego_parentis.sync.core.ReplicationRules
import dev.schrammel.protego_parentis.sync.core.SyncRole
import java.time.Instant
import java.util.UUID

/**
 * The shared plumbing for everything that is a *stream* rather than a setting.
 *
 * Chat, requests and the audit log are all the same shape: entries that are
 * created once, keyed by an id, and rarely changed afterwards. They differ from
 * the rules in two ways that matter for cost:
 *
 * - **They only grow.** So each entry's id begins with the second it was made
 *   (see [ReplicationRules.streamId]), which is what lets a replica decide from
 *   a path alone whether an entry is recent enough to still compare or old
 *   enough to drop. Without that, keeping the digest bounded would need an index
 *   beside the state, and every replica would have to agree on it too.
 *
 * - **Old entries stop mattering.** A chat message from three months ago is not
 *   worth a repair, and a family's whole history is not worth re-hashing every
 *   half hour. [ReplicationRules.RECENT_WINDOW_SECONDS] draws that line once for
 *   all three.
 *
 * Writing an entry is a handful of registers under one id, written in a single
 * batch so they share a clock tick — and, because they share a long path prefix,
 * compress hard on the wire. See [StateCodec].
 */
class StreamReplication(context: Context) {

    private val appContext = context.applicationContext
    private val state = ReplicatedStateStore(appContext)

    /** This device's id, or null if the keystore will not answer. */
    private fun origin(): String? =
        LocalDeviceId.current()

    /** This device's role, for the write-time gate every write is now held to. */
    private fun role(): SyncRole = RoleStore(appContext).roleOrChild.toSyncRole()

    /**
     * Adds one entry to [section], returning its id.
     *
     * The id carries the creation second and a short random tail — short because
     * it is paid for in every path under the entry, and a few random characters
     * alongside a timestamp is already far more than a family needs to avoid a
     * collision.
     */
    fun append(section: String, fields: Map<String, String?>): String? {
        val origin = origin() ?: return null
        val id = ReplicationRules.streamId(
            seconds = Instant.now().epochSecond,
            unique = UUID.randomUUID().toString().take(ID_TAIL),
        )
        val root = "$section${ReplicationRules.SEPARATOR}$id"
        val withAuthor = fields + (FIELD_BY to origin)
        state.writeAll(withAuthor.mapKeys { "$root${ReplicationRules.SEPARATOR}${it.key}" }, origin, role())
        return id
    }

    /** Changes fields of an entry that already exists — answering a request, say. */
    fun amend(section: String, id: String, fields: Map<String, String?>): Boolean {
        val origin = origin() ?: return false
        val root = "$section${ReplicationRules.SEPARATOR}$id"
        return state.writeAll(
            fields.mapKeys { "$root${ReplicationRules.SEPARATOR}${it.key}" },
            origin,
            role(),
        ).isNotEmpty()
    }

    /** Every entry in [section], newest first, as a map of field to value. */
    fun entries(section: String): List<StreamEntry> =
        state.idsUnder(section)
            .map { id -> StreamEntry(id, state.children("$section${ReplicationRules.SEPARATOR}$id")) }
            .filter { it.fields.isNotEmpty() }
            .sortedByDescending { it.createdAt }

    /** One entry, or null if this replica has never heard of it. */
    fun entry(section: String, id: String): StreamEntry? {
        val fields = state.children("$section${ReplicationRules.SEPARATOR}$id")
        return if (fields.isEmpty()) null else StreamEntry(id, fields)
    }

    companion object {
        /**
         * Which device wrote the entry, stored rather than inferred.
         *
         * The op carries an origin, but that is transport detail the state map
         * does not keep — and "did I say this" is a question the conversation
         * and the request list both need to answer long after the op is gone.
         */
        const val FIELD_BY = "by"

        /** Enough randomness beside a second-resolution timestamp; no more. */
        private const val ID_TAIL = 6
    }
}

/** One entry of a replicated stream. */
data class StreamEntry(val id: String, val fields: Map<String, String>) {

    /** When it was made, from the id itself. */
    val createdAt: Long get() = id.substringBefore('-').toLongOrNull() ?: 0L

    fun text(field: String): String? = fields[field]

    fun number(field: String): Long? = fields[field]?.toLongOrNull()

    fun flag(field: String): Boolean = fields[field]?.toBooleanStrictOrNull() ?: false

    /** The device that wrote it, or empty for an entry from an older build. */
    val by: String get() = fields[StreamReplication.FIELD_BY].orEmpty()
}
