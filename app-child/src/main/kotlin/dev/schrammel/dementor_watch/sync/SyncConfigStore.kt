package dev.schrammel.dementor_watch.sync

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import dev.schrammel.dementor_watch.model.Defaults
import dev.schrammel.dementor_watch.security.DeviceRole
import dev.schrammel.dementor_watch.security.KeystoreSigner
import dev.schrammel.dementor_watch.security.RoleStore
import dev.schrammel.dementor_watch.security.SecretVault
import dev.schrammel.dementor_watch.sync.SyncSettings.Companion.DEFAULT_SERVER
import java.time.Instant
import java.util.UUID

/** Everything needed to reach the family's topic. */
data class SyncSettings(
    val enabled: Boolean,
    /** Which backend carries this family's messages. */
    val transport: TransportKind,
    val serverUrl: String,
    val topic: String,
    val passphrase: String,
    /**
     * This phone's identity on the topic: the fingerprint of its signing key.
     *
     * Not a preference and not editable. [deviceName] is what people read;
     * this is what the other phones check.
     */
    val deviceId: String,
    /** What the family calls this phone. Shown on every other phone's device list. */
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

    /** The half of the settings a person can pass to another person. */
    fun token(): FamilyToken? =
        runCatching { FamilyToken(transport, serverUrl, topic) }.getOrNull()

    companion object {
        const val DEFAULT_SERVER = "https://ntfy.sh"
    }
}

/**
 * Stores the sync settings, the replay-guard state and this phone's name.
 *
 * The passphrase is kept sealed by [SecretVault] rather than in plain text. That
 * does not put it beyond a rooted phone's owner — they can ask this app to
 * decrypt — but it does stop the secret being copied off the device and used from
 * somewhere else, which is the difference between one compromised handset and a
 * permanently compromised family.
 *
 * The signing key underneath [SyncSettings.deviceId] is stronger still: it is
 * generated non-exportable inside the keystore and cannot leave the phone at all.
 */
class SyncConfigStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun load(): SyncSettings = SyncSettings(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        transport = TransportKind.parse(prefs.getString(KEY_TRANSPORT, null))
            ?: TransportKind.NTFY,
        serverUrl = prefs.getString(KEY_SERVER, null)?.trimEnd('/') ?: DEFAULT_SERVER,
        topic = prefs.getString(KEY_TOPIC, null).orEmpty(),
        passphrase = passphrase(),
        deviceId = deviceId(),
        deviceName = deviceName(),
    )

    /**
     * This phone's fingerprint, computed once per process.
     *
     * Reading it means a call into the keystore, and `load()` is called from
     * screen renders. It never changes while the app is running — a new identity
     * means a reinstall — so caching it keeps a binder call off the main thread.
     */
    private fun deviceId(): String {
        cachedDeviceId?.let { return it }
        val value = runCatching { KeystoreSigner().deviceId.value }.getOrDefault("")
        cachedDeviceId = value
        return value
    }

    /**
     * Joins a family, or leaves one.
     *
     * Takes a token rather than three loose strings, so a caller cannot pair a
     * server with the wrong backend — the token is the unit a family is described
     * in, and it either parses as a whole or not at all.
     */
    fun join(token: FamilyToken, passphrase: String) {
        save(
            enabled = true,
            transport = token.kind,
            serverUrl = token.serverUrl,
            topic = token.topic,
            passphrase = passphrase,
        )
    }

    /**
     * Moves the family to a different address, keeping its identity.
     *
     * The topic and passphrase are deliberately untouched: they are who the family
     * is, and this changes only where it meets. Everything already enrolled stays
     * enrolled, so a family that moves together does not re-approve anybody.
     */
    fun move(token: FamilyToken) {
        prefs.edit {
            putString(KEY_TRANSPORT, token.kind.name)
            putString(KEY_SERVER, token.serverUrl.trimEnd('/'))
            putString(KEY_TOPIC, token.topic)
        }
        // From now, not from the beginning of time. There is nothing at the new
        // address from before this moment, and asking for everything ever would
        // fetch whatever else has used that topic name.
        recordPolledAt(Instant.now())
    }

    fun save(
        enabled: Boolean,
        transport: TransportKind,
        serverUrl: String,
        topic: String,
        passphrase: String,
    ) {
        prefs.edit {
            putBoolean(KEY_ENABLED, enabled)
            putString(KEY_TRANSPORT, transport.name)
            putString(KEY_SERVER, serverUrl.trim().trimEnd('/').ifBlank { DEFAULT_SERVER })
            putString(KEY_TOPIC, topic.trim())
        }
        // Only overwrite when something was actually typed, so reopening the
        // screen and saving does not wipe a stored passphrase.
        if (passphrase.isNotBlank()) storePassphrase(passphrase)
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

    // ---------- the passphrase ----------

    /**
     * Reads the passphrase, migrating a plain-text one written by an older build.
     *
     * The migration runs once, on the first read after upgrading, and removes the
     * plain-text copy as it goes. Leaving both would mean the sealed version
     * protected nothing.
     */
    private fun passphrase(): String {
        prefs.getString(KEY_PASSPHRASE_LEGACY, null)?.takeIf(String::isNotBlank)?.let { legacy ->
            storePassphrase(legacy)
            prefs.edit { remove(KEY_PASSPHRASE_LEGACY) }
            return legacy
        }
        val sealed = prefs.getString(KEY_PASSPHRASE_SEALED, null) ?: return ""

        // Cached against the sealed text, so a passphrase changed on another
        // screen is picked up while an unchanged one costs no keystore call.
        // The plain value lives no longer than the process, and only ever in a
        // process that could decrypt it again anyway.
        cachedPassphrase?.let { (forSealed, plain) -> if (forSealed == sealed) return plain }

        val plain = SecretVault.open(sealed).orEmpty()
        cachedPassphrase = sealed to plain
        return plain
    }

    /**
     * Seals and stores the passphrase.
     *
     * If the keystore refuses — it does, occasionally, on devices whose lock
     * screen has been reset — the passphrase is not written at all. Falling back
     * to plain text would silently undo the whole point of this; the family sees
     * sync as unconfigured and types it again.
     */
    private fun storePassphrase(value: String) {
        val sealed = SecretVault.seal(value)
        prefs.edit {
            if (sealed == null) remove(KEY_PASSPHRASE_SEALED) else putString(KEY_PASSPHRASE_SEALED, sealed)
            remove(KEY_PASSPHRASE_LEGACY)
        }
        cachedPassphrase = sealed?.let { it to value }
    }

    /**
     * Forgets the family's passphrase.
     *
     * Separate from [save], which deliberately ignores a blank one so that
     * reopening a screen and saving does not wipe what is stored. Leaving is the
     * one case where blank means blank.
     */
    fun forgetPassphrase() {
        prefs.edit {
            remove(KEY_PASSPHRASE_SEALED)
            remove(KEY_PASSPHRASE_LEGACY)
        }
        cachedPassphrase = null
    }

    /** Whether the phone can keep a passphrase at all, so a screen can say so. */
    fun canKeepSecrets(): Boolean = SecretVault.isAvailable()

    // ---------- this phone's name ----------

    /**
     * What the family calls this phone.
     *
     * Defaulted from the hardware model rather than left blank: "Pixel 7" is a
     * better starting point than an empty field, and a parent looking at three
     * pending devices needs something to tell them apart before they have named
     * anything.
     */
    var deviceName: String
        get() = deviceName()
        set(value) {
            val trimmed = value.trim().take(MAX_NAME_LENGTH)
            if (trimmed.isEmpty()) return
            prefs.edit { putString(KEY_DEVICE_NAME, trimmed) }
        }

    private fun deviceName(): String {
        prefs.getString(KEY_DEVICE_NAME, null)?.takeIf(String::isNotBlank)?.let { return it }

        val suffix = when (RoleStore(appContext).roleOrChild) {
            DeviceRole.CHILD -> ""
            DeviceRole.PARENT -> " (parent)"
        }
        val generated = (Build.MODEL?.takeIf(String::isNotBlank) ?: "Phone") + suffix
        prefs.edit { putString(KEY_DEVICE_NAME, generated) }
        return generated
    }

    // ---------- replay guard state ----------

    fun seenMessageIds(): List<String> =
        prefs.getString(KEY_SEEN, null)?.split(SEPARATOR)?.filter(String::isNotBlank).orEmpty()

    fun rememberSeen(ids: List<String>) {
        prefs.edit { putString(KEY_SEEN, ids.joinToString(SEPARATOR)) }
    }

    fun lastPolledAt(): Instant = Instant.ofEpochMilli(prefs.getLong(KEY_LAST_POLL, 0L))

    fun recordPolledAt(at: Instant) {
        prefs.edit { putLong(KEY_LAST_POLL, at.toEpochMilli()) }
    }

    // ---------- heartbeat throttle ----------

    /**
     * Whether enough time has passed to say hello again.
     *
     * A clock that has moved backwards would otherwise suppress heartbeats until
     * it caught up, which is precisely the situation where a parent most wants to
     * hear from the device. Any negative gap is treated as due.
     */
    fun heartbeatDue(now: Instant): Boolean {
        val last = prefs.getLong(KEY_LAST_HEARTBEAT, 0L)
        if (last <= 0L) return true
        val elapsed = now.toEpochMilli() - last
        return elapsed < 0L || elapsed >= Defaults.HEARTBEAT_INTERVAL_MINUTES * MILLIS_PER_MINUTE
    }

    fun recordHeartbeatAt(at: Instant) {
        prefs.edit { putLong(KEY_LAST_HEARTBEAT, at.toEpochMilli()) }
    }

    // ---------- announcements ----------

    /**
     * Whether to introduce this phone again.
     *
     * Repeated on a slow cycle rather than sent once, because the phone that needs
     * to hear it may not have been configured yet when the first one went out.
     */
    fun announceDue(now: Instant): Boolean {
        val last = prefs.getLong(KEY_LAST_ANNOUNCE, 0L)
        if (last <= 0L) return true
        val elapsed = now.toEpochMilli() - last
        return elapsed < 0L || elapsed >= ANNOUNCE_INTERVAL_MINUTES * MILLIS_PER_MINUTE
    }

    fun recordAnnouncedAt(at: Instant) {
        prefs.edit { putLong(KEY_LAST_ANNOUNCE, at.toEpochMilli()) }
    }

    /** Forces the next tick to introduce this phone, after a settings change. */
    fun announceSoon() {
        prefs.edit { remove(KEY_LAST_ANNOUNCE) }
    }

    // ---------- replication ----------

    /**
     * Whether to publish a state digest again.
     *
     * Slower than the poll on purpose. The digest exists to catch the case where
     * ops were missed entirely — a phone off for longer than the relay keeps
     * them — which is rare and not urgent. Sending one every tick would spend a
     * message on saying "still agreed" dozens of times an hour.
     */
    fun digestDue(now: Instant): Boolean {
        val last = prefs.getLong(KEY_LAST_DIGEST, 0L)
        if (last <= 0L) return true
        val elapsed = now.toEpochMilli() - last
        // A negative gap means the clock moved backwards, which on the managed
        // phone is something the person being limited can arrange. Treated as
        // due rather than as a reason to never send one again.
        return elapsed < 0L || elapsed >= DIGEST_INTERVAL_MINUTES * MILLIS_PER_MINUTE
    }

    fun recordDigestAt(at: Instant) {
        prefs.edit { putLong(KEY_LAST_DIGEST, at.toEpochMilli()) }
    }

    private companion object {
        /** Process-wide: every screen builds its own store. */
        @Volatile
        var cachedDeviceId: String? = null

        @Volatile
        var cachedPassphrase: Pair<String, String>? = null

        const val FILE_NAME = "sync"
        const val SEPARATOR = ","
        const val MAX_NAME_LENGTH = 40
        const val MILLIS_PER_MINUTE = 60_000L
        const val ANNOUNCE_INTERVAL_MINUTES = 60L

        /** Often enough to heal within an hour, rare enough to be nearly free. */
        const val DIGEST_INTERVAL_MINUTES = 30L

        const val KEY_ENABLED = "enabled"
        const val KEY_SERVER = "server"
        const val KEY_TOPIC = "topic"
        const val KEY_TRANSPORT = "transport"

        /** Written by builds before the vault existed; migrated away on first read. */
        const val KEY_PASSPHRASE_LEGACY = "passphrase"
        const val KEY_PASSPHRASE_SEALED = "passphrase_sealed"

        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_SEEN = "seen_ids"
        const val KEY_LAST_POLL = "last_poll"
        const val KEY_LAST_HEARTBEAT = "last_heartbeat"
        const val KEY_LAST_ANNOUNCE = "last_announce"
        const val KEY_LAST_DIGEST = "last_digest"
    }
}
