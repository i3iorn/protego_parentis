package dev.schrammel.protego_parentis.sync

import android.app.NotificationManager
import android.content.Context
import dev.schrammel.protego_parentis.enforcement.RequestNotifier
import dev.schrammel.protego_parentis.sync.core.ReplicationRules
import dev.schrammel.protego_parentis.sync.core.Signer
import dev.schrammel.protego_parentis.sync.core.StateOp
import dev.schrammel.protego_parentis.sync.core.deviceId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

/**
 * [ChatInboundHandler.announceNewChat] decides whether an arriving chat line
 * is this phone's own by asking its signer for a device id. Before this class
 * took a [Signer] rather than the concrete `KeystoreSigner`, that call could
 * only ever be exercised against the real Android Keystore — which Robolectric
 * does not provide — so `signer.deviceId` always threw, `self` was always
 * null, and no test could tell whether the self-exclusion below actually
 * excluded anything. A fake [Signer] makes that filter reachable at all.
 */
@RunWith(RobolectricTestRunner::class)
class ChatInboundHandlerTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager get() = checkNotNull(context.getSystemService(NotificationManager::class.java))

    // The Lamport clock behind ReplicatedStateStore is shared per process, not
    // per instance (see ReplicatedStateStoreTest), so a stale one from an
    // earlier test would misorder these writes.
    @Before
    fun reset() = ReplicatedStateStore(context).forgetAll()

    @After
    fun tearDown() = ReplicatedStateStore(context).forgetAll()

    private class FakeSigner : Signer {
        private val keys = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

        override val publicKey: ByteArray get() = keys.public.encoded
        override fun sign(payload: ByteArray): ByteArray = ByteArray(0)
    }

    private fun handler(signer: Signer) =
        ChatInboundHandler(context, RosterStore(context), RequestNotifier(context), signer)

    private fun textOp(id: String, origin: String, text: String, lamport: Long) = StateOp(
        path = "${ReplicationRules.CHAT}${ReplicationRules.SEPARATOR}$id${ReplicationRules.SEPARATOR}text",
        value = text,
        lamport = lamport,
        origin = origin,
    )

    @Test
    fun `a chat line this phone's own signer wrote is not announced`() {
        val signer = FakeSigner()
        // Written and received exactly as it would be over the wire, so the
        // handler's own lookup by id finds it.
        val applied = ReplicatedStateStore(context).receive(
            listOf(textOp(id = "1-own", origin = signer.deviceId.value, text = "hi", lamport = 1)),
        )

        handler(signer).announceNewChat(applied)

        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun `a chat line from another device is announced`() {
        val signer = FakeSigner()
        val applied = ReplicatedStateStore(context).receive(
            listOf(textOp(id = "1-other", origin = "some-other-device", text = "hi from someone else", lamport = 1)),
        )

        handler(signer).announceNewChat(applied)

        assertEquals(1, shadowOf(manager).allNotifications.size)
    }
}
