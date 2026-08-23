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

    /** Child → parents: an app appeared on this phone that was not here before. */
    APP_INSTALLED,

    /** Parent → child: that new app is fine, or it is not. */
    APP_DECIDED,

    /**
     * Either → everyone: this is who I am.
     *
     * Carries a name and a public key so the other phones can offer to enrol it.
     * The only message type whose sender is, by definition, not yet trusted.
     */
    DEVICE_ANNOUNCE,
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
    /**
     * The sender's device id — the fingerprint of [senderKey].
     *
     * Not a name and not a choice: a device that claims an id whose fingerprint
     * does not match the key it signed with is refused. See [RosterRules].
     */
    val sender: String,
    val sentAt: Instant,
    val fields: Map<String, String> = emptyMap(),
    /** SPKI public key, base64. Absent only on messages from a build before signing. */
    val senderKey: String? = null,
    /** What the sender says it is. Believed only while enrolling it. */
    val senderRole: SyncRole? = null,
    /** What the sender calls itself, for showing in a list of family devices. */
    val senderName: String? = null,
) {
    fun field(name: String): String? = fields[name]?.takeIf(String::isNotBlank)

    fun longField(name: String): Long? = field(name)?.toLongOrNull()
}
