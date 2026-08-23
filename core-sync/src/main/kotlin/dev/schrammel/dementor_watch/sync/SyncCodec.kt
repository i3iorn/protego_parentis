package dev.schrammel.dementor_watch.sync

import java.time.Instant

/**
 * Turns a [SyncMessage] into the string that gets encrypted, and back.
 *
 * A small hand-rolled format rather than a JSON library, for the same reason the
 * provisioning payload is: this must round-trip identically on every device in a
 * family, including ones on older builds, and the shape is a flat map of strings.
 *
 * Parsing treats its input as hostile. Anything malformed yields null rather than
 * an exception — the plaintext came off a public topic, and although it decrypted
 * with the family key, a buggy or newer sender is entirely possible.
 */
object SyncCodec {

    private const val RECORD_SEPARATOR = '\n'
    private const val FIELD_SEPARATOR = '\t'

    private const val KEY_ID = "id"
    private const val KEY_TYPE = "type"
    private const val KEY_SENDER = "sender"
    private const val KEY_SENT_AT = "sentAt"
    private const val KEY_SENDER_KEY = "senderKey"
    private const val KEY_SENDER_ROLE = "senderRole"
    private const val KEY_SENDER_NAME = "senderName"
    private const val FIELD_PREFIX = "f."

    fun encode(message: SyncMessage): String = buildString {
        appendLine("$KEY_ID$FIELD_SEPARATOR${escape(message.id)}")
        appendLine("$KEY_TYPE$FIELD_SEPARATOR${message.type.name}")
        appendLine("$KEY_SENDER$FIELD_SEPARATOR${escape(message.sender)}")
        appendLine("$KEY_SENT_AT$FIELD_SEPARATOR${message.sentAt.toEpochMilli()}")
        // Part of the body, so the signature covers them. A sender that could
        // change its own role or key after signing would defeat the roster.
        message.senderKey?.let { appendLine("$KEY_SENDER_KEY$FIELD_SEPARATOR$it") }
        message.senderRole?.let { appendLine("$KEY_SENDER_ROLE$FIELD_SEPARATOR${it.name}") }
        message.senderName?.let {
            appendLine("$KEY_SENDER_NAME$FIELD_SEPARATOR${escape(it)}")
        }
        message.fields.forEach { (name, value) ->
            appendLine("$FIELD_PREFIX${escape(name)}$FIELD_SEPARATOR${escape(value)}")
        }
    }

    fun decode(plaintext: String): SyncMessage? {
        val entries = plaintext
            .split(RECORD_SEPARATOR)
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val at = line.indexOf(FIELD_SEPARATOR)
                if (at <= 0) null else line.substring(0, at) to unescape(line.substring(at + 1))
            }
            .toMap()

        val id = entries[KEY_ID]?.takeIf(String::isNotBlank) ?: return null
        val sender = entries[KEY_SENDER]?.takeIf(String::isNotBlank) ?: return null
        val sentAt = entries[KEY_SENT_AT]?.toLongOrNull() ?: return null

        // An unrecognised type means a newer sender. Ignore the message rather
        // than guessing at what it wanted.
        val type = SyncMessageType.entries.firstOrNull { it.name == entries[KEY_TYPE] }
            ?: return null

        return SyncMessage(
            id = id,
            type = type,
            sender = sender,
            sentAt = Instant.ofEpochMilli(sentAt),
            fields = entries
                .filterKeys { it.startsWith(FIELD_PREFIX) }
                .mapKeys { (key, _) -> unescape(key.removePrefix(FIELD_PREFIX)) },
            senderKey = entries[KEY_SENDER_KEY]?.takeIf(String::isNotBlank),
            senderRole = SyncRole.parse(entries[KEY_SENDER_ROLE]),
            senderName = entries[KEY_SENDER_NAME]?.takeIf(String::isNotBlank),
        )
    }

    // Values may contain anything a parent types, including newlines and tabs.
    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace("\t", "\\t")

    private fun unescape(value: String): String = buildString {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char != '\\' || index == value.lastIndex) {
                append(char)
                index++
                continue
            }
            when (value[index + 1]) {
                'n' -> append('\n')
                't' -> append('\t')
                '\\' -> append('\\')
                else -> append(value[index + 1])
            }
            index += 2
        }
    }
}
