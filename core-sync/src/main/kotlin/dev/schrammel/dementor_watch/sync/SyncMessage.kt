package dev.schrammel.dementor_watch.sync

import java.time.Instant

/** What a message is for. Unknown values are ignored rather than guessed at. */
enum class SyncMessageType {
    /** Child → parents: someone is asking for more time. */
    REQUEST_RAISED,

    /** Parent → child: a request was answered. */
    REQUEST_DECIDED,

    /** Parent → child: extra time, granted without being asked. */
    BONUS_GRANTED,

    /** Child → parents: still alive, here is the state of things. */
    HEARTBEAT,
}

/**
 * One decrypted message.
 *
 * [sentAt] and [id] are not decoration: on a public topic anyone can capture a
 * message and publish it again later. Together they let the receiver reject a
 * replay — see [ReplayGuard].
 *
 * [fields] is deliberately a flat string map. The devices in a family will not
 * all be on the same build, and a strict schema would mean an older phone
 * refusing a message it could have partly understood.
 */
data class SyncMessage(
    val id: String,
    val type: SyncMessageType,
    val sender: String,
    val sentAt: Instant,
    val fields: Map<String, String> = emptyMap(),
) {
    fun field(name: String): String? = fields[name]?.takeIf(String::isNotBlank)

    fun longField(name: String): Long? = field(name)?.toLongOrNull()
}
