package dev.schrammel.dementor_watch.sync

/**
 * How ops and digests are written down for the wire.
 *
 * Compact on purpose, and more compact than it looks. A batch of ops is the
 * message this protocol sends most often, and the whole reason for replacing
 * whole-state pushes was the size of what went out for a one-field change — so
 * three redundancies are squeezed out of every batch:
 *
 * **Shared path prefixes.** Ops in a batch overwhelmingly share long prefixes:
 * every member of one category, every app limit, every field of one request.
 * Each path is written as "how many characters I share with the previous path,
 * then the rest" — front coding. `policy/categories/games/packages/com.foo`
 * followed by `…/com.bar` costs the shared 32 characters once rather than
 * twice. Ops are sorted by path before encoding purely to maximise that
 * overlap, which is free: they are commutative, so order carries no meaning.
 *
 * **Repeated origin.** Every op in a batch comes from this device, so the
 * twelve-character device id is written once and left empty afterwards.
 *
 * **Repeated clock.** One edit is one clock tick, so a batch of forty registers
 * changed together carries one Lamport value rather than forty.
 *
 * Together these typically cut a batch to well under half. The separators are
 * the ASCII control characters meant for exactly this, and they are escaped in
 * values rather than forbidden — a chat message containing a newline is
 * ordinary, and an encoding that truncated at one would be a bug reachable by
 * typing.
 *
 * Anything malformed is skipped rather than failing the batch. Devices in a
 * family will not all be on the same build, and one op a newer phone wrote in a
 * way this one cannot read must not cost it the other forty in the same message.
 */
object StateCodec {

    private const val OP_SEPARATOR = '\u001E'
    private const val FIELD_SEPARATOR = '\u001F'
    private const val DIGEST_SEPARATOR = ','

    /**
     * A null value and the empty string are different states — "deleted" and
     * "set to nothing" — so the tombstone is a flag of its own rather than an
     * absent field.
     */
    private const val PRESENT = "1"
    private const val TOMBSTONE = "0"

    fun encodeOps(ops: List<StateOp>): String {
        // Sorted only to make the front coding below pay: adjacent paths share
        // more. Ops are commutative, so nothing downstream can tell or care.
        val sorted = ops.sortedBy { it.path }
        var previousPath = ""
        var previousLamport: Long? = null
        var previousOrigin: String? = null

        return sorted.joinToString(OP_SEPARATOR.toString()) { op ->
            val shared = sharedPrefix(previousPath, op.path)
            val line = listOf(
                shared.toString(),
                escape(op.path.substring(shared)),
                if (op.value == null) TOMBSTONE else PRESENT,
                escape(op.value.orEmpty()),
                // Empty means "same as the op before", which in a batch written
                // by one device in one tick is every op after the first.
                if (op.lamport == previousLamport) "" else op.lamport.toString(),
                if (op.origin == previousOrigin) "" else escape(op.origin),
            ).joinToString(FIELD_SEPARATOR.toString())

            previousPath = op.path
            previousLamport = op.lamport
            previousOrigin = op.origin
            line
        }
    }

    fun decodeOps(raw: String?): List<StateOp> {
        if (raw.isNullOrEmpty()) return emptyList()

        var previousPath = ""
        var previousLamport: Long? = null
        var previousOrigin: String? = null
        val decoded = mutableListOf<StateOp>()

        raw.split(OP_SEPARATOR).forEach { line ->
            if (line.isEmpty()) return@forEach
            val parts = line.split(FIELD_SEPARATOR)
            if (parts.size < 6) return@forEach

            // A shared length longer than the previous path means the batch was
            // written against a different one — a corrupted or truncated
            // message. Skipping keeps the rest readable; guessing would invent
            // a path and write it into state, which is far worse.
            val shared = parts[0].toIntOrNull() ?: return@forEach
            if (shared < 0 || shared > previousPath.length) return@forEach

            val path = previousPath.take(shared) + unescape(parts[1])
            if (path.isEmpty()) return@forEach

            val lamport = if (parts[4].isEmpty()) previousLamport else parts[4].toLongOrNull()
            if (lamport == null) return@forEach

            val origin = if (parts[5].isEmpty()) previousOrigin else unescape(parts[5])
            if (origin.isNullOrEmpty()) return@forEach

            decoded += StateOp(
                path = path,
                value = if (parts[2] == TOMBSTONE) null else unescape(parts[3]),
                lamport = lamport,
                origin = origin,
            )
            previousPath = path
            previousLamport = lamport
            previousOrigin = origin
        }
        return decoded
    }

    fun encodeDigest(buckets: List<Long>): String = buckets.joinToString(DIGEST_SEPARATOR.toString())

    fun decodeDigest(raw: String?): List<Long> {
        if (raw.isNullOrBlank()) return emptyList()
        val parts = raw.split(DIGEST_SEPARATOR)
        // All or nothing: a digest with one unreadable bucket would compare as a
        // difference in that bucket and send a repair for state that is fine.
        return parts.map { it.toLongOrNull() ?: return emptyList() }
    }

    fun encodeBuckets(buckets: Set<Int>): String =
        buckets.sorted().joinToString(DIGEST_SEPARATOR.toString())

    fun decodeBuckets(raw: String?): Set<Int> {
        if (raw.isNullOrBlank()) return emptySet()
        return raw.split(DIGEST_SEPARATOR)
            .mapNotNull { it.toIntOrNull() }
            .filter { it in 0 until StateDigest.BUCKETS }
            .toSet()
    }

    /**
     * How many ops fit in one message.
     *
     * A bound rather than a target. Repairing a long-divergent replica can
     * produce more ops than any relay will carry in one body, so the caller
     * sends several — see the publisher's batching. Sized well below ntfy's
     * limit, since the encoded batch is then sealed and base64'd on top.
     */
    const val MAX_OPS_PER_MESSAGE = 120

    private fun sharedPrefix(previous: String, current: String): Int {
        val limit = minOf(previous.length, current.length)
        var shared = 0
        while (shared < limit && previous[shared] == current[shared]) shared++
        return shared
    }

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace(OP_SEPARATOR.toString(), "\\r")
        .replace(FIELD_SEPARATOR.toString(), "\\f")

    private fun unescape(value: String): String = buildString {
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character != '\\' || index == value.lastIndex) {
                append(character)
                index++
                continue
            }
            when (value[index + 1]) {
                'r' -> append(OP_SEPARATOR)
                'f' -> append(FIELD_SEPARATOR)
                '\\' -> append('\\')
                else -> append(value[index + 1])
            }
            index += 2
        }
    }
}
