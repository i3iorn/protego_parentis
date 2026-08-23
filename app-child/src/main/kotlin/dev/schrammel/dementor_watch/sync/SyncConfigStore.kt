package dev.schrammel.dementor_watch.sync

import android.content.Context
import androidx.core.content.edit
import dev.schrammel.dementor_watch.sync.SyncSettings.Companion.DEFAULT_SERVER
import java.time.Instant
import java.util.UUID

/** Everything needed to reach the family's topic. */
data class SyncSettings(
    val enabled: Boolean,
    val serverUrl: String,
    val topic: String,
    val passphrase: String,
    /** Identifies this phone in messages, so a parent can tell who said what. */
    val deviceName: String,
) {
    /**
     * Usable only when all three are present. A topic without a passphrase would
     * publish family activity in clear text to a public server, so the app refuses
     * to run half-configured rather than falling back to no encryption.
     */
    val isUsable: Boolean
        get() = enabled &&
            serverUrl.startsWith("https://") &&
            topic.isNotBlank() &&
            passphrase.isNotBlank()

    companion object {
        const val DEFAULT_SERVER = "https://ntfy.sh"
    }
}

/**
 * Stores the sync settings and the replay-guard state.
 *
 * The passphrase is kept in the app's private preferences. That protects it from
 * other apps but not from someone holding an unlocked, rooted phone — which is
 * why compromise of a device means changing the passphrase everywhere, and why
 * the settings screen sits behind the parent PIN.
 */
class SyncConfigStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun load(): SyncSettings = SyncSettings(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        serverUrl = prefs.getString(KEY_SERVER, null)?.trimEnd('/') ?: DEFAULT_SERVER,
        topic = prefs.getString(KEY_TOPIC, null).orEmpty(),
        passphrase = prefs.getString(KEY_PASSPHRASE, null).orEmpty(),
        deviceName = deviceName(),
    )

    fun save(enabled: Boolean, serverUrl: String, topic: String, passphrase: String) {
        prefs.edit {
            putBoolean(KEY_ENABLED, enabled)
            putString(KEY_SERVER, serverUrl.trim().trimEnd('/').ifBlank { DEFAULT_SERVER })
            putString(KEY_TOPIC, topic.trim())
            // Only overwrite when something was actually typed, so reopening the
            // screen and saving does not wipe a stored passphrase.
            if (passphrase.isNotBlank()) putString(KEY_PASSPHRASE, passphrase)
        }
    }

    /**
     * A random topic name.
     *
     * Long and random because on a public server the topic name is the only thing
     * standing between a stranger and a stream of encrypted family traffic. They
     * still could not read it, but they could see that it exists and how often it
     * moves.
     */
    fun suggestTopic(): String = "dementor-" + UUID.randomUUID().toString().replace("-", "")

    // ---------- replay guard state ----------

    fun seenMessageIds(): List<String> =
        prefs.getString(KEY_SEEN, null)?.split(SEPARATOR)?.filter(String::isNotBlank).orEmpty()

    fun rememberSeen(ids: List<String>) {
        prefs.edit { putString(KEY_SEEN, ids.joinToString(SEPARATOR)) }
    }

    fun lastPolledAt(): Instant =
        Instant.ofEpochMilli(prefs.getLong(KEY_LAST_POLL, 0L))

    fun recordPolledAt(at: Instant) {
        prefs.edit { putLong(KEY_LAST_POLL, at.toEpochMilli()) }
    }

    private fun deviceName(): String {
        prefs.getString(KEY_DEVICE_NAME, null)?.let { return it }
        val generated = "child-" + UUID.randomUUID().toString().take(DEVICE_NAME_LENGTH)
        prefs.edit { putString(KEY_DEVICE_NAME, generated) }
        return generated
    }

    private companion object {
        const val FILE_NAME = "sync"
        const val SEPARATOR = ","
        const val DEVICE_NAME_LENGTH = 8

        const val KEY_ENABLED = "enabled"
        const val KEY_SERVER = "server"
        const val KEY_TOPIC = "topic"
        const val KEY_PASSPHRASE = "passphrase"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_SEEN = "seen_ids"
        const val KEY_LAST_POLL = "last_poll"
    }
}
