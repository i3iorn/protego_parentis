package dev.schrammel.dementor_watch.sync

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Which devices this phone trusts, and which have asked to be trusted.
 *
 * The roster is what turns a shared passphrase into a set of identifiable family
 * members. Enrolment is deliberately manual: a device announces itself, it appears
 * here as pending with its fingerprint, and a person compares that fingerprint
 * against the one shown on the device itself before approving.
 *
 * Trust-on-first-use would be easier and would give most of the protection away,
 * because the attacker this defends against is inside the house and can wait for
 * the right moment to announce.
 */
class RosterStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    val devices: List<KnownDevice>
        get() = decodeKnown(prefs.getString(KEY_KNOWN, null))

    val pending: List<PendingDevice>
        get() = decodePending(prefs.getString(KEY_PENDING, null))

    fun parents(): List<KnownDevice> = devices.filter { it.role == SyncRole.PARENT }

    fun child(): KnownDevice? = devices.firstOrNull { it.role == SyncRole.CHILD }

    /**
     * Records a device that signed correctly but nobody has vouched for.
     *
     * Capped, because the pending list is the one thing on this screen an outsider
     * can influence: anyone who learns the topic *and* the passphrase could
     * otherwise fill it with invented devices until the real one is impossible to
     * find. Newest wins, so a genuine device announcing now is always visible.
     */
    fun notice(device: PendingDevice) = synchronized(LOCK) {
        if (devices.any { it.id == device.id }) return@synchronized
        // Turned away already. Without this, "not mine" lasted until the device
        // announced again — about a minute — and a phone that kept announcing
        // could push the one the family was waiting for out of a capped list.
        if (device.id.value in refused) return@synchronized

        val existing = pending.filterNot { it.id == device.id }
        writePending((existing + device).takeLast(MAX_PENDING))
    }

    /**
     * Enrols a pending device. The role and key are taken from what was announced
     * and shown to whoever approved it, not re-read afterwards.
     */
    fun approve(id: DeviceId, name: String, now: Instant = Instant.now()): KnownDevice? =
        synchronized(LOCK) {
            val candidate = pending.firstOrNull { it.id == id } ?: return@synchronized null
            val device = KnownDevice(
                id = candidate.id,
                name = name.ifBlank { candidate.name }.ifBlank { candidate.id.value },
                role = candidate.role,
                publicKey = candidate.publicKey,
                approvedAt = now,
                lastHeardAt = now,
            )
            writeKnown(devices.filterNot { it.id == id } + device)
            writePending(pending.filterNot { it.id == id })
            device
        }

    /** Turns a device away and remembers doing so, until [unrefuse]. */
    /**
     * Enrols a device on a trusted parent's word, with no pending entry.
     *
     * The other path through this class asks a person to compare a fingerprint;
     * this one has already had that done, on the phone that vouched. Everything
     * that made it safe there — the comparison, the PIN — happened once, and this
     * is the family being told the outcome.
     */
    fun enrol(vouch: Vouch, now: Instant = Instant.now()) = synchronized(LOCK) {
        if (devices.any { it.id == vouch.id }) return@synchronized

        writeKnown(
            devices + KnownDevice(
                id = vouch.id,
                name = vouch.name.ifBlank { vouch.id.value },
                role = vouch.role,
                publicKey = vouch.publicKey,
                approvedAt = now,
                lastHeardAt = null,
            ),
        )
        // No longer waiting for anybody, and no longer turned away either: the
        // family has spoken, and a stale row in either list would only confuse
        // whoever opens the screen next.
        writePending(pending.filterNot { it.id == vouch.id })
        prefs.edit { putStringSet(KEY_REFUSED, refused - vouch.id.value) }
    }

    fun reject(id: DeviceId) = synchronized(LOCK) {
        writePending(pending.filterNot { it.id == id })
        prefs.edit { putStringSet(KEY_REFUSED, refused + id.value) }
    }

    /** Forgets every device, approved, pending and refused. */
    fun forgetAll() = synchronized(LOCK) {
        prefs.edit { clear() }
    }

    /** Devices this family has turned away. */
    val refused: Set<String>
        get() = prefs.getStringSet(KEY_REFUSED, emptySet()).orEmpty()

    /**
     * Lets a device ask again.
     *
     * Rejecting is the right default for a phone nobody recognises, and it has to
     * be reversible: the commonest reason for a stranger in the list is a family
     * member reinstalling, and a mistake there would otherwise be permanent.
     */
    fun unrefuse(id: DeviceId) = synchronized(LOCK) {
        prefs.edit { putStringSet(KEY_REFUSED, refused - id.value) }
    }

    /** Removes a device's trust. It can ask again, and be looked at again. */
    fun revoke(id: DeviceId) = synchronized(LOCK) {
        writeKnown(devices.filterNot { it.id == id })
        writePending(pending.filterNot { it.id == id })
        // Not added to the refused list: removing a device is usually a prelude to
        // adding it back, and a removal that also made it invisible would look
        // like the app had broken.
    }

    /**
     * Notes that a trusted device is still talking, and what it now calls itself.
     *
     * The name travels on every message, so a phone renamed on its own settings
     * screen shows up renamed everywhere without a separate exchange. Only an
     * already-approved device can do this, and it could already say anything its
     * role permits — a confusing name is not a new power.
     */
    fun observed(id: DeviceId, name: String?, at: Instant) = synchronized(LOCK) {
        val current = devices
        if (current.none { it.id == id }) return@synchronized
        val chosen = name?.trim()?.takeIf(String::isNotEmpty)

        writeKnown(
            current.map { device ->
                if (device.id != id) {
                    device
                } else {
                    device.copy(lastHeardAt = at, name = chosen ?: device.name)
                }
            },
        )
    }

    fun rename(id: DeviceId, name: String) = synchronized(LOCK) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@synchronized
        writeKnown(devices.map { if (it.id == id) it.copy(name = trimmed) else it })
    }

    // ---------- storage ----------

    private fun writeKnown(devices: List<KnownDevice>) {
        prefs.edit {
            putString(
                KEY_KNOWN,
                JSONArray(
                    devices.map { device ->
                        JSONObject().apply {
                            put(KEY_ID, device.id.value)
                            put(KEY_NAME, device.name)
                            put(KEY_ROLE, device.role.name)
                            put(KEY_PUBLIC_KEY, device.publicKey)
                            put(KEY_APPROVED_AT, device.approvedAt.toEpochMilli())
                            device.lastHeardAt?.let { put(KEY_LAST_HEARD, it.toEpochMilli()) }
                        }
                    },
                ).toString(),
            )
        }
    }

    private fun writePending(devices: List<PendingDevice>) {
        prefs.edit {
            putString(
                KEY_PENDING,
                JSONArray(
                    devices.map { device ->
                        JSONObject().apply {
                            put(KEY_ID, device.id.value)
                            put(KEY_NAME, device.name)
                            put(KEY_ROLE, device.role.name)
                            put(KEY_PUBLIC_KEY, device.publicKey)
                            put(KEY_FIRST_HEARD, device.firstHeardAt.toEpochMilli())
                        }
                    },
                ).toString(),
            )
        }
    }

    private fun decodeKnown(raw: String?): List<KnownDevice> = rows(raw).mapNotNull { json ->
        val id = json.optString(KEY_ID).takeIf(String::isNotBlank) ?: return@mapNotNull null
        val role = SyncRole.parse(json.optString(KEY_ROLE)) ?: return@mapNotNull null
        val publicKey = json.optString(KEY_PUBLIC_KEY).takeIf(String::isNotBlank)
            ?: return@mapNotNull null

        KnownDevice(
            id = DeviceId(id),
            name = json.optString(KEY_NAME).ifBlank { id },
            role = role,
            publicKey = publicKey,
            approvedAt = Instant.ofEpochMilli(json.optLong(KEY_APPROVED_AT, 0L)),
            lastHeardAt = json.optLong(KEY_LAST_HEARD, 0L)
                .takeIf { it > 0L }
                ?.let(Instant::ofEpochMilli),
        )
    }

    private fun decodePending(raw: String?): List<PendingDevice> = rows(raw).mapNotNull { json ->
        val id = json.optString(KEY_ID).takeIf(String::isNotBlank) ?: return@mapNotNull null
        val role = SyncRole.parse(json.optString(KEY_ROLE)) ?: return@mapNotNull null
        val publicKey = json.optString(KEY_PUBLIC_KEY).takeIf(String::isNotBlank)
            ?: return@mapNotNull null

        PendingDevice(
            id = DeviceId(id),
            name = json.optString(KEY_NAME),
            role = role,
            publicKey = publicKey,
            firstHeardAt = Instant.ofEpochMilli(json.optLong(KEY_FIRST_HEARD, 0L)),
        )
    }

    private fun rows(raw: String?): List<JSONObject> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull(array::optJSONObject)
    }

    private companion object {
        /** Shared across instances: several screens construct their own store. */
        val LOCK = Any()

        const val FILE_NAME = "roster"
        const val KEY_KNOWN = "known"
        const val KEY_PENDING = "pending"
        const val KEY_REFUSED = "refused"

        /** Enough for a large family plus a few reinstalls; not enough to flood. */
        const val MAX_PENDING = 10

        const val KEY_ID = "id"
        const val KEY_NAME = "name"
        const val KEY_ROLE = "role"
        const val KEY_PUBLIC_KEY = "publicKey"
        const val KEY_APPROVED_AT = "approvedAt"
        const val KEY_LAST_HEARD = "lastHeardAt"
        const val KEY_FIRST_HEARD = "firstHeardAt"
    }
}
