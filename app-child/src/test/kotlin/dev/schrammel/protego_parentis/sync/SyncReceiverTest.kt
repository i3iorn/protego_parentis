package dev.schrammel.protego_parentis.sync

import android.content.Context
import dev.schrammel.protego_parentis.audit.Audit
import dev.schrammel.protego_parentis.model.AuditAction
import dev.schrammel.protego_parentis.model.GrantTarget
import dev.schrammel.protego_parentis.rules.ContextRuleStore
import dev.schrammel.protego_parentis.rules.GrantStore
import dev.schrammel.protego_parentis.rules.LocalPolicyStore
import dev.schrammel.protego_parentis.rules.snapshot
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.LocalDeviceId
import dev.schrammel.protego_parentis.security.RoleStore
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_APPS_JSON
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_BLOCKED
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_LATITUDE
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_LONGITUDE
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_MINUTES
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_OPS
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_PACKAGE
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_PLACE
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_POLICY_JSON
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_RADIUS
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_REVOKED_ID
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_TARGET
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_TARGET_LABEL
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_VOUCHED_ID
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_VOUCHED_KEY
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_VOUCHED_ROLE
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.sync.core.Fingerprints
import dev.schrammel.protego_parentis.sync.core.MessageCrypto
import dev.schrammel.protego_parentis.sync.core.MessageFragments
import dev.schrammel.protego_parentis.sync.core.MessageSignatures
import dev.schrammel.protego_parentis.sync.core.ReplicationRules
import dev.schrammel.protego_parentis.sync.core.Signer
import dev.schrammel.protego_parentis.sync.core.StateCodec
import dev.schrammel.protego_parentis.sync.core.StateOp
import dev.schrammel.protego_parentis.sync.core.SyncCodec
import dev.schrammel.protego_parentis.sync.core.SyncKey
import dev.schrammel.protego_parentis.sync.core.SyncMessage
import dev.schrammel.protego_parentis.sync.core.SyncMessageType
import dev.schrammel.protego_parentis.sync.core.SyncRole
import dev.schrammel.protego_parentis.sync.core.Vouch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.UUID

/**
 * [SyncReceiver] is where every message from another family device is
 * decrypted, replay-checked, role-admitted, and dispatched — and, until now,
 * completely untested. Delivers real signed-and-sealed envelopes through
 * [SyncReceiver.receiveEnvelope], the same entry point production code uses,
 * rather than calling any private dispatch method directly.
 *
 * `applyVouch`/`applyRevoke` used to ask [dev.schrammel.protego_parentis.security.KeystoreSigner]
 * directly for this phone's own id, which Robolectric cannot produce (no
 * `AndroidKeyStore` provider) — see [LocalDeviceId]'s own doc comment for why
 * every other call site in this app already goes through it instead. Switched
 * those two to the same seam so they could be tested at all; behaviour in
 * production is unchanged, since [LocalDeviceId.current] falls through to the
 * real keystore call whenever no test override is set.
 */
@RunWith(RobolectricTestRunner::class)
class SyncReceiverTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    // SyncReceiver.receiveEnvelope stamps replay-admission with the real wall
    // clock (Instant.now()), not an injectable one — so a fixed literal here
    // ages past ReplayGuard.FRESHNESS_WINDOW (30 minutes) the moment enough
    // wall-clock time has passed since it was written, and every message
    // this test delivers is silently rejected as too old regardless of its
    // content. Reading it fresh keeps every delivered message within the
    // window no matter when the test actually runs.
    private val now: Instant get() = Instant.now()

    private val key: SyncKey = MessageCrypto.deriveKey("correct horse battery staple", "dementor-test-topic")

    private fun publisher() = SyncPublisher(context)
    private fun receiver() = SyncReceiver(context, publisher())
    private fun roster() = RosterStore(context)

    @Before
    fun setUp() {
        LocalDeviceId.useInTests("receiving-device")
    }

    @After
    fun tearDown() {
        LocalDeviceId.useInTests(null)
        roster().forgetAll()
    }

    // ---------- helpers ----------

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private class RawSigner(private val keys: KeyPair) : Signer {
        override val publicKey: ByteArray get() = keys.public.encoded
        override fun sign(payload: ByteArray): ByteArray =
            Signature.getInstance("SHA256withECDSA").run {
                initSign(keys.private)
                update(payload)
                sign()
            }
    }

    private fun enroll(keys: KeyPair, role: SyncRole, name: String = "A phone") {
        roster().enrol(
            Vouch(
                id = Fingerprints.of(keys.public.encoded),
                name = name,
                role = role,
                publicKey = Fingerprints.encodeKey(keys.public.encoded),
            ),
            now,
        )
    }

    private fun message(
        keys: KeyPair,
        type: SyncMessageType,
        claimedRole: SyncRole,
        fields: Map<String, String> = emptyMap(),
        sentAt: Instant = now,
    ) = SyncMessage(
        id = "m-${UUID.randomUUID()}",
        type = type,
        sender = Fingerprints.of(keys.public.encoded).value,
        sentAt = sentAt,
        fields = fields,
        senderKey = Fingerprints.encodeKey(keys.public.encoded),
        senderRole = claimedRole,
        senderName = "A phone",
    )

    /** Signs and seals [message] exactly as [SyncPublisher] would, then delivers it. */
    private fun deliver(message: SyncMessage, keys: KeyPair): Boolean {
        val body = SyncCodec.encode(message)
        val plaintext = MessageSignatures.sign(RawSigner(keys), body)
        val envelope = MessageCrypto.seal(key, plaintext)
        return receiver().receiveEnvelope(key, envelope)
    }

    /**
     * As [deliver], but split the way [SyncPublisher] would for a payload too
     * big for one wire message — every piece but the last must be ignored,
     * and only the last completes the group.
     */
    private fun deliverChunked(
        message: SyncMessage,
        keys: KeyPair,
        maxWireChars: Int,
        order: (List<String>) -> List<String> = { it },
        receiver: SyncReceiver = receiver(),
    ): Boolean {
        val body = SyncCodec.encode(message)
        val plaintext = MessageSignatures.sign(RawSigner(keys), body)
        val envelope = MessageCrypto.seal(key, plaintext)
        val chunks = MessageFragments.split(message.id, envelope, maxWireChars)
        return order(chunks).map { receiver.receiveEnvelope(key, it) }.any { it }
    }

    // ---------- applyVouch ----------

    @Test
    fun `a parent accepts a vouch for a new device from an enrolled parent`() {
        RoleStore(context).choose(DeviceRole.PARENT)
        val voucher = keyPair()
        enroll(voucher, SyncRole.PARENT, "Mum's phone")
        val newDevice = keyPair()

        val applied = deliver(
            message(
                voucher,
                SyncMessageType.DEVICE_VOUCHED,
                SyncRole.PARENT,
                fields = mapOf(
                    FIELD_VOUCHED_ID to Fingerprints.of(newDevice.public.encoded).value,
                    FIELD_VOUCHED_ROLE to SyncRole.PARENT.name,
                    FIELD_VOUCHED_KEY to Fingerprints.encodeKey(newDevice.public.encoded),
                    FIELD_TARGET_LABEL to "Dad's phone",
                ),
            ),
            voucher,
        )

        assertTrue(applied)
        assertTrue(roster().devices.any { it.id == Fingerprints.of(newDevice.public.encoded) })
    }

    @Test
    fun `a vouch signed by a device on the roster as a child is refused`() {
        RoleStore(context).choose(DeviceRole.PARENT)
        val childDevice = keyPair()
        enroll(childDevice, SyncRole.CHILD, "The managed phone")
        val newDevice = keyPair()

        // DEVICE_VOUCHED's expected sender is a parent; a device enrolled as
        // the child claiming otherwise is refused before applyVouch ever runs.
        val applied = deliver(
            message(
                childDevice,
                SyncMessageType.DEVICE_VOUCHED,
                SyncRole.PARENT,
                fields = mapOf(
                    FIELD_VOUCHED_ID to Fingerprints.of(newDevice.public.encoded).value,
                    FIELD_VOUCHED_ROLE to SyncRole.PARENT.name,
                    FIELD_VOUCHED_KEY to Fingerprints.encodeKey(newDevice.public.encoded),
                ),
            ),
            childDevice,
        )

        assertFalse(applied)
        assertTrue(roster().devices.none { it.id == Fingerprints.of(newDevice.public.encoded) })
    }

    // ---------- applyRevoke ----------

    @Test
    fun `a parent removes an enrolled device named in a revoke from another parent`() {
        RoleStore(context).choose(DeviceRole.PARENT)
        val voucher = keyPair()
        enroll(voucher, SyncRole.PARENT, "Mum's phone")
        val target = keyPair()
        enroll(target, SyncRole.PARENT, "Grandma's phone")

        val applied = deliver(
            message(
                voucher,
                SyncMessageType.DEVICE_REVOKED,
                SyncRole.PARENT,
                fields = mapOf(FIELD_REVOKED_ID to Fingerprints.of(target.public.encoded).value),
            ),
            voucher,
        )

        assertTrue(applied)
        assertTrue(roster().devices.none { it.id == Fingerprints.of(target.public.encoded) })
    }

    /**
     * [dev.schrammel.protego_parentis.sync.core.Enrolment.shouldAcceptRevoke] is
     * the whole security boundary here: revoking the managed phone stays a
     * local, PIN-gated action, never something a message may cause — including
     * on the managed phone's own receiver.
     */
    @Test
    fun `a revoke naming the managed child device is refused`() {
        RoleStore(context).choose(DeviceRole.PARENT)
        val voucher = keyPair()
        enroll(voucher, SyncRole.PARENT, "Mum's phone")
        val child = keyPair()
        enroll(child, SyncRole.CHILD, "The managed phone")

        val applied = deliver(
            message(
                voucher,
                SyncMessageType.DEVICE_REVOKED,
                SyncRole.PARENT,
                fields = mapOf(FIELD_REVOKED_ID to Fingerprints.of(child.public.encoded).value),
            ),
            voucher,
        )

        assertFalse(applied)
        assertTrue(roster().devices.any { it.id == Fingerprints.of(child.public.encoded) })
    }

    // ---------- applyPolicyUpdate ----------

    @Test
    fun `a policy update based on the current version is applied`() {
        RoleStore(context).choose(DeviceRole.CHILD)
        val store = LocalPolicyStore(context)
        val currentVersion = store.load().version
        val parent = keyPair()
        enroll(parent, SyncRole.PARENT, "Mum's phone")

        val snapshot = store.snapshot().copy(version = currentVersion, dailyLimitMinutes = 45)
        val applied = deliver(
            message(
                parent,
                SyncMessageType.POLICY_UPDATED,
                SyncRole.PARENT,
                fields = mapOf(FIELD_POLICY_JSON to snapshot.encode()),
            ),
            parent,
        )

        assertTrue(applied)
        assertEquals(45, store.dailyLimitMinutes)
    }

    @Test
    fun `a policy update based on a stale version is refused, not silently overwritten`() {
        RoleStore(context).choose(DeviceRole.CHILD)
        val store = LocalPolicyStore(context)
        store.dailyLimitMinutes = 20
        val parent = keyPair()
        enroll(parent, SyncRole.PARENT, "Mum's phone")

        // A version this phone's own store has moved past already, simulating a
        // parent editing from a copy that raced a local edit made in the meantime.
        val stale = store.snapshot().copy(version = store.load().version + 1, dailyLimitMinutes = 999)
        val applied = deliver(
            message(
                parent,
                SyncMessageType.POLICY_UPDATED,
                SyncRole.PARENT,
                fields = mapOf(FIELD_POLICY_JSON to stale.encode()),
            ),
            parent,
        )

        assertFalse(applied)
        assertEquals(20, store.dailyLimitMinutes)
    }

    // ---------- withinCapacity (state-ops DoS bound) ----------

    /**
     * [ReplicationRules.capacityOf] bounds how many *new* keys one section may
     * hold, so a device in the family cannot flood every replica by inventing
     * keys forever. Telemetry is the smallest section (500) and is the one the
     * managed phone alone may write, which is also the device this bound
     * matters most for — its own compromise is the threat model.
     */
    @Test
    fun `state ops past a section's capacity are dropped, not applied`() {
        RoleStore(context).choose(DeviceRole.PARENT)
        val child = keyPair()
        enroll(child, SyncRole.CHILD, "The managed phone")
        val cap = ReplicationRules.capacityOf("${ReplicationRules.TELEMETRY}/x")
        val senderId = Fingerprints.of(child.public.encoded).value

        val ops = (0 until cap + 10).map { index ->
            StateOp(
                path = "${ReplicationRules.TELEMETRY}/key-$index",
                value = "v",
                lamport = index.toLong(),
                origin = senderId,
            )
        }

        val applied = deliver(
            message(
                child,
                SyncMessageType.STATE_OPS,
                SyncRole.CHILD,
                fields = mapOf(FIELD_OPS to StateCodec.encodeOps(ops)),
            ),
            child,
        )

        assertTrue(applied)
        val stored = ReplicatedStateStore(context).entries.keys
            .count { it.startsWith("${ReplicationRules.TELEMETRY}${ReplicationRules.SEPARATOR}") }
        assertEquals("only the first `cap` new keys should have been accepted", cap, stored)
    }

    // ---------- messages from a stranger ----------

    @Test
    fun `a message from a device nobody has vouched for is not applied`() {
        RoleStore(context).choose(DeviceRole.PARENT)
        val stranger = keyPair()

        val applied = deliver(
            message(stranger, SyncMessageType.HEARTBEAT, SyncRole.CHILD),
            stranger,
        )

        assertFalse(applied)
    }

    @Test
    fun `an enrolled child's heartbeat is applied by a parent`() {
        RoleStore(context).choose(DeviceRole.PARENT)
        val child = keyPair()
        enroll(child, SyncRole.CHILD)

        val applied = deliver(
            message(child, SyncMessageType.HEARTBEAT, SyncRole.CHILD),
            child,
        )

        // Confirms the full pipeline (decrypt, replay-guard, roster-admit,
        // dispatch) reaches applyAsParent -> rememberHeartbeat for an
        // already-enrolled sender, the ordinary case every poll exercises.
        assertTrue(applied)
        assertTrue(HeartbeatStore(context).latest != null)
    }

    // ---------- rememberNewApp / the app inventory it keeps fresh ----------

    @Test
    fun `an app-install notice updates an already-synced inventory`() {
        RoleStore(context).choose(DeviceRole.PARENT)
        val child = keyPair()
        enroll(child, SyncRole.CHILD)
        val childId = Fingerprints.of(child.public.encoded)
        AppInventoryStore(context).setApps(childId, listOf(AppInventoryEntry("com.example.old", "Old App")))

        val applied = deliver(
            message(
                child,
                SyncMessageType.APP_INSTALLED,
                SyncRole.CHILD,
                fields = mapOf(
                    FIELD_PACKAGE to "com.example.new",
                    FIELD_TARGET_LABEL to "New App",
                ),
            ),
            child,
        )

        assertTrue(applied)
        val cached = AppInventoryStore(context).apps(childId)
        assertTrue("the existing entry must survive", cached.any { it.packageName == "com.example.old" })
        assertTrue(
            "the newly installed app must now be offered too",
            cached.any { it.packageName == "com.example.new" && it.label == "New App" },
        )
    }

    /**
     * The guard rememberNewApp's own doc comment describes: a lone install
     * notice must never look like the whole inventory to a picker that has
     * never asked for a full one yet, or the "request list" prompt that
     * fixes an empty picker would disappear while the picker is still
     * missing everything installed before this one notice.
     */
    @Test
    fun `an app-install notice does not seed an inventory before a full sync ever happened`() {
        RoleStore(context).choose(DeviceRole.PARENT)
        val child = keyPair()
        enroll(child, SyncRole.CHILD)
        val childId = Fingerprints.of(child.public.encoded)

        val applied = deliver(
            message(
                child,
                SyncMessageType.APP_INSTALLED,
                SyncRole.CHILD,
                fields = mapOf(FIELD_PACKAGE to "com.example.new", FIELD_TARGET_LABEL to "New App"),
            ),
            child,
        )

        assertTrue(applied)
        assertTrue(AppInventoryStore(context).apps(childId).isEmpty())
    }

    @Test
    fun `a second install notice for the same package replaces it rather than duplicating it`() {
        RoleStore(context).choose(DeviceRole.PARENT)
        val child = keyPair()
        enroll(child, SyncRole.CHILD)
        val childId = Fingerprints.of(child.public.encoded)
        AppInventoryStore(context).setApps(childId, listOf(AppInventoryEntry("com.example.app", "Old Label")))

        deliver(
            message(
                child,
                SyncMessageType.APP_INSTALLED,
                SyncRole.CHILD,
                fields = mapOf(FIELD_PACKAGE to "com.example.app", FIELD_TARGET_LABEL to "New Label"),
            ),
            child,
        )

        val cached = AppInventoryStore(context).apps(childId)
        assertEquals(1, cached.count { it.packageName == "com.example.app" })
        assertEquals("New Label", cached.single { it.packageName == "com.example.app" }.label)
    }

    // ---------- a reply too big for one wire message (#85 resurfacing after PR #195) ----------

    /**
     * PR #195 raised the client-side line-length bound, but a full inventory
     * still clears ntfy's own `message-size-limit` (4,096 bytes by default) —
     * a server-side ceiling this app never controlled, and one ntfy enforces
     * by silently turning an over-limit publish into a download attachment
     * instead of delivering it as a message, which nothing here fetches. This
     * exercises the actual fix: [MessageFragments] splits the sealed envelope
     * the way [SyncPublisher] now does, and [SyncReceiver] must reassemble it
     * before a large [SyncMessageType.APP_INVENTORY] reply can be applied.
     */
    @Test
    fun `a large app inventory split across several wire messages is reassembled and applied`() {
        RoleStore(context).choose(DeviceRole.PARENT)
        val child = keyPair()
        enroll(child, SyncRole.CHILD)
        val childId = Fingerprints.of(child.public.encoded)

        val apps = (1..300).map { AppInventoryEntry("com.example.app$it", "App $it") }
        val applied = deliverChunked(
            message(
                child,
                SyncMessageType.APP_INVENTORY,
                SyncRole.CHILD,
                fields = mapOf(FIELD_APPS_JSON to AppInventoryCodec.encode(apps)),
            ),
            child,
            maxWireChars = 500,
        )

        assertTrue(applied)
        assertEquals(apps.toSet(), AppInventoryStore(context).apps(childId).toSet())
    }

    @Test
    fun `pieces of a large inventory arriving out of order still reassemble`() {
        RoleStore(context).choose(DeviceRole.PARENT)
        val child = keyPair()
        enroll(child, SyncRole.CHILD)
        val childId = Fingerprints.of(child.public.encoded)

        val apps = (1..300).map { AppInventoryEntry("com.example.app$it", "App $it") }
        val applied = deliverChunked(
            message(
                child,
                SyncMessageType.APP_INVENTORY,
                SyncRole.CHILD,
                fields = mapOf(FIELD_APPS_JSON to AppInventoryCodec.encode(apps)),
            ),
            child,
            maxWireChars = 500,
            order = { it.shuffled(kotlin.random.Random(7)) },
        )

        assertTrue(applied)
        assertEquals(apps.toSet(), AppInventoryStore(context).apps(childId).toSet())
    }

    @Test
    fun `a small inventory that already fits is delivered as a single wire message, unchanged`() {
        RoleStore(context).choose(DeviceRole.PARENT)
        val child = keyPair()
        enroll(child, SyncRole.CHILD)
        val childId = Fingerprints.of(child.public.encoded)

        val apps = listOf(AppInventoryEntry("com.example.one", "One"))
        val applied = deliverChunked(
            message(
                child,
                SyncMessageType.APP_INVENTORY,
                SyncRole.CHILD,
                fields = mapOf(FIELD_APPS_JSON to AppInventoryCodec.encode(apps)),
            ),
            child,
            maxWireChars = NtfyTransport.MAX_ENVELOPE_CHARS,
        )

        assertTrue(applied)
        assertEquals(apps, AppInventoryStore(context).apps(childId))
    }

    // ---------- applyPlaceCreateRequest ----------

    @Test
    fun `a parent's coordinates create a place on the managed phone`() {
        RoleStore(context).choose(DeviceRole.CHILD)
        val parent = keyPair()
        enroll(parent, SyncRole.PARENT, "Mum's phone")

        val applied = deliver(
            message(
                parent,
                SyncMessageType.PLACE_CREATE_REQUESTED,
                SyncRole.PARENT,
                fields = mapOf(
                    FIELD_PLACE to "Grandma's",
                    FIELD_LATITUDE to "51.5074",
                    FIELD_LONGITUDE to "-0.1278",
                    FIELD_RADIUS to "150",
                ),
            ),
            parent,
        )

        assertTrue(applied)
        val created = ContextRuleStore(context).places.singleOrNull { it.name == "Grandma's" }
        assertEquals(51.5074, created?.latitude)
        assertEquals(-0.1278, created?.longitude)
        assertEquals(150, created?.radiusMetres)
        assertTrue(Audit.entries(context).any { it.action == AuditAction.PLACE_ADDED && it.subject == "Grandma's" })
    }

    @Test
    fun `coordinates out of range are refused rather than saved`() {
        RoleStore(context).choose(DeviceRole.CHILD)
        val parent = keyPair()
        enroll(parent, SyncRole.PARENT, "Mum's phone")

        val applied = deliver(
            message(
                parent,
                SyncMessageType.PLACE_CREATE_REQUESTED,
                SyncRole.PARENT,
                fields = mapOf(
                    FIELD_PLACE to "Nowhere",
                    FIELD_LATITUDE to "500",
                    FIELD_LONGITUDE to "-0.1278",
                    FIELD_RADIUS to "150",
                ),
            ),
            parent,
        )

        assertFalse(applied)
        assertTrue(ContextRuleStore(context).places.none { it.name == "Nowhere" })
    }

    @Test
    fun `a radius under the privacy floor is refused rather than clamped`() {
        RoleStore(context).choose(DeviceRole.CHILD)
        val parent = keyPair()
        enroll(parent, SyncRole.PARENT, "Mum's phone")

        val applied = deliver(
            message(
                parent,
                SyncMessageType.PLACE_CREATE_REQUESTED,
                SyncRole.PARENT,
                fields = mapOf(
                    FIELD_PLACE to "Too precise",
                    FIELD_LATITUDE to "51.5074",
                    FIELD_LONGITUDE to "-0.1278",
                    FIELD_RADIUS to "1",
                ),
            ),
            parent,
        )

        assertFalse(applied)
        assertTrue(ContextRuleStore(context).places.none { it.name == "Too precise" })
    }

    @Test
    fun `only a parent may create a place remotely`() {
        RoleStore(context).choose(DeviceRole.CHILD)
        val child = keyPair()
        enroll(child, SyncRole.CHILD)

        val applied = deliver(
            message(
                child,
                SyncMessageType.PLACE_CREATE_REQUESTED,
                SyncRole.CHILD,
                fields = mapOf(
                    FIELD_PLACE to "Forged",
                    FIELD_LATITUDE to "51.5074",
                    FIELD_LONGITUDE to "-0.1278",
                    FIELD_RADIUS to "150",
                ),
            ),
            child,
        )

        assertFalse(applied)
        assertTrue(ContextRuleStore(context).places.none { it.name == "Forged" })
    }

    // ---------- applyBonus ----------

    @Test
    fun `bonus time from a parent is granted and recorded`() {
        RoleStore(context).choose(DeviceRole.CHILD)
        val parent = keyPair()
        enroll(parent, SyncRole.PARENT, "Mum's phone")

        val applied = deliver(
            message(
                parent,
                SyncMessageType.BONUS_GRANTED,
                SyncRole.PARENT,
                fields = mapOf(
                    FIELD_MINUTES to "15",
                    FIELD_TARGET to SyncTargets.encode(GrantTarget.Device),
                ),
            ),
            parent,
        )

        assertTrue(applied)
        val granted = GrantStore(context).active(Instant.now())
            .filter { it.target == GrantTarget.Device }
            .sumOf { it.amount.inWholeMinutes }
        assertTrue("the grant, and the notification alongside it, must not throw", granted >= 15)
        assertTrue(Audit.entries(context).any { it.action == AuditAction.BONUS_GRANTED })
    }

    // ---------- applyAppDecision ----------

    @Test
    fun `a parent's decision on a new app is applied and recorded`() {
        RoleStore(context).choose(DeviceRole.CHILD)
        val parent = keyPair()
        enroll(parent, SyncRole.PARENT, "Mum's phone")

        val applied = deliver(
            message(
                parent,
                SyncMessageType.APP_DECIDED,
                SyncRole.PARENT,
                fields = mapOf(FIELD_PACKAGE to "com.example.new", FIELD_BLOCKED to "true"),
            ),
            parent,
        )

        assertTrue(applied)
        assertTrue("com.example.new" in LocalPolicyStore(context).blockedPackages)
        assertTrue(Audit.entries(context).any { it.action == AuditAction.APP_BLOCK_CHANGED })
    }

    // ---------- onStateChanged: a remote policy write ----------

    @Test
    fun `a policy write arriving as state ops is pulled in without throwing`() {
        RoleStore(context).choose(DeviceRole.CHILD)
        val parent = keyPair()
        enroll(parent, SyncRole.PARENT, "Mum's phone")

        // Only that this reaches onStateChanged's POLICY branch (and the new
        // notifyRuleChanged call inside it) without throwing — mayWrite lets
        // a parent write anywhere under the section regardless of the exact
        // suffix, so this need not be a real, recognised policy field to
        // prove the dispatch path itself is sound.
        val ops = listOf(
            StateOp(
                path = "${ReplicationRules.POLICY}${ReplicationRules.SEPARATOR}probe",
                value = "1",
                lamport = 1L,
                origin = Fingerprints.of(parent.public.encoded).value,
            ),
        )

        val applied = deliver(
            message(
                parent,
                SyncMessageType.STATE_OPS,
                SyncRole.PARENT,
                fields = mapOf(FIELD_OPS to StateCodec.encodeOps(ops)),
            ),
            parent,
        )

        assertTrue(applied)
    }
}
