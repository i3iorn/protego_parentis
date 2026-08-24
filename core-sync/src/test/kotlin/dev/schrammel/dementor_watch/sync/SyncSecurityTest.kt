package dev.schrammel.dementor_watch.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

private const val PASSPHRASE = "correct horse battery staple"
private const val TOPIC = "dementor-abc123"

class MessageCryptoTest {

    private val key = MessageCrypto.deriveKey(PASSPHRASE, TOPIC)

    @Test
    fun `a sealed message opens again with the same key`() {
        val sealed = MessageCrypto.seal(key, "grant 30 minutes")

        assertEquals(
            DecryptResult.Opened("grant 30 minutes"),
            MessageCrypto.open(key, sealed),
        )
    }

    @Test
    fun `the ciphertext does not leak the message`() {
        val sealed = MessageCrypto.seal(key, "Clash Royale")

        assertFalse(sealed.contains("Clash"))
    }

    @Test
    fun `the same message sealed twice looks different`() {
        // A fixed nonce under one key destroys GCM outright, and repeated
        // ciphertext would also let an observer count identical messages.
        val first = MessageCrypto.seal(key, "heartbeat")
        val second = MessageCrypto.seal(key, "heartbeat")

        assertNotEquals(first, second)
    }

    @Test
    fun `a different passphrase cannot open it`() {
        val sealed = MessageCrypto.seal(key, "secret")
        val wrong = MessageCrypto.deriveKey("a completely different one", TOPIC)

        assertEquals(
            DecryptResult.Failed(DecryptFailure.NOT_AUTHENTIC),
            MessageCrypto.open(wrong, sealed),
        )
    }

    @Test
    fun `the same passphrase on another topic cannot open it`() {
        // The topic is the salt, so two families sharing a passphrase still get
        // different keys.
        val sealed = MessageCrypto.seal(key, "secret")
        val otherFamily = MessageCrypto.deriveKey(PASSPHRASE, "dementor-zzz999")

        assertEquals(
            DecryptResult.Failed(DecryptFailure.NOT_AUTHENTIC),
            MessageCrypto.open(otherFamily, sealed),
        )
    }

    @Test
    fun `a tampered message is refused, not silently altered`() {
        // Without authentication an attacker could flip bits in a real message
        // and the phone would act on the result.
        val sealed = MessageCrypto.seal(key, "grant 5 minutes")
        val flipped = sealed.toCharArray().also { it[it.size - 4] = if (it[it.size - 4] == 'A') 'B' else 'A' }

        assertEquals(
            DecryptResult.Failed(DecryptFailure.NOT_AUTHENTIC),
            MessageCrypto.open(key, String(flipped)),
        )
    }

    @Test
    fun `junk from the public topic is refused without throwing`() {
        // Anyone can publish anything to the topic. None of it may crash us.
        listOf("", "hello", "!!!!", "AAAA", "not base64 at all ~~~").forEach { junk ->
            val result = MessageCrypto.open(key, junk)
            assertTrue("threw or opened junk: $junk", result is DecryptResult.Failed)
        }
    }

    @Test
    fun `a short passphrase is refused outright`() {
        assertThrows(IllegalArgumentException::class.java) {
            MessageCrypto.deriveKey("short", TOPIC)
        }
    }

    @Test
    fun `deriving twice from the same inputs gives the same key`() {
        val again = MessageCrypto.deriveKey(PASSPHRASE, TOPIC)
        val sealed = MessageCrypto.seal(key, "same key please")

        assertEquals(DecryptResult.Opened("same key please"), MessageCrypto.open(again, sealed))
    }

    @Test
    fun `unicode and newlines survive the round trip`() {
        val text = "Kino am Samstag 🍿\nund Eis"

        assertEquals(
            DecryptResult.Opened(text),
            MessageCrypto.open(key, MessageCrypto.seal(key, text)),
        )
    }
}

class SyncCodecTest {

    private val message = SyncMessage(
        id = "m-1",
        type = SyncMessageType.REQUEST_RAISED,
        sender = "child-phone",
        sentAt = Instant.parse("2026-08-23T18:00:00Z"),
        fields = mapOf("minutes" to "15", "reason" to "Finishing a match"),
    )

    @Test
    fun `a message survives a round trip`() {
        assertEquals(message, SyncCodec.decode(SyncCodec.encode(message)))
    }

    @Test
    fun `awkward text in a field survives`() {
        // A parent's prize text can contain anything they type.
        val awkward = message.copy(
            fields = mapOf("prize" to "Cinema\tSaturday\nand popcorn \\ yes"),
        )

        assertEquals(awkward, SyncCodec.decode(SyncCodec.encode(awkward)))
    }

    @Test
    fun `an unknown message type is ignored rather than guessed at`() {
        val encoded = SyncCodec.encode(message).replace("REQUEST_RAISED", "SOMETHING_NEWER")

        assertNull(SyncCodec.decode(encoded))
    }

    @Test
    fun `malformed input yields null rather than throwing`() {
        listOf("", "garbage", "id\t", "type\tREQUEST_RAISED").forEach {
            assertNull("threw or accepted: $it", SyncCodec.decode(it))
        }
    }
}

class ReplayGuardTest {

    private val now: Instant = Instant.parse("2026-08-23T18:00:00Z")

    private fun message(
        id: String = "m-1",
        sentAt: Instant = now,
        type: SyncMessageType = SyncMessageType.BONUS_GRANTED,
    ) = SyncMessage(
        id = id,
        type = type,
        sender = "parent",
        sentAt = sentAt,
    )

    @Test
    fun `conversation survives a phone that was asleep, commands do not`() {
        val threeHoursAgo = now.minusSeconds(3 * 60 * 60)

        // A parent whose phone dozed all afternoon should still see what was said.
        assertTrue(
            ReplayGuard.admit(
                message(sentAt = threeHoursAgo, type = SyncMessageType.CHAT_MESSAGE),
                now,
                emptySet(),
            ).isAccepted,
        )
        // The same age, for something that would actually do something, is refused.
        assertEquals(
            Admission.Reject(RejectReason.TOO_OLD),
            ReplayGuard.admit(message(sentAt = threeHoursAgo), now, emptySet()),
        )
    }

    @Test
    fun `even a message has an outer limit`() {
        val ancient = now.minusSeconds(
            ReplayGuard.CONVERSATION_WINDOW.inWholeSeconds + 60,
        )
        assertEquals(
            Admission.Reject(RejectReason.TOO_OLD),
            ReplayGuard.admit(
                message(sentAt = ancient, type = SyncMessageType.CHAT_MESSAGE),
                now,
                emptySet(),
            ),
        )
    }

    @Test
    fun `a replayed message is still refused, whatever its age`() {
        // The longer window is safe only because this stays true.
        assertEquals(
            Admission.Reject(RejectReason.ALREADY_SEEN),
            ReplayGuard.admit(
                message(id = "c-1", type = SyncMessageType.CHAT_MESSAGE),
                now,
                setOf("c-1"),
            ),
        )
    }

    @Test
    fun `a fresh unseen message is accepted`() {
        assertTrue(ReplayGuard.admit(message(), now, emptySet()).isAccepted)
    }

    @Test
    fun `yesterday's captured grant is refused today`() {
        // The attack this exists for: republish a genuine "grant 30 minutes".
        val old = message(sentAt = now.minusSeconds(60 * 60 * 24))

        assertEquals(
            Admission.Reject(RejectReason.TOO_OLD),
            ReplayGuard.admit(old, now, emptySet()),
        )
    }

    @Test
    fun `a message already acted on is refused`() {
        assertEquals(
            Admission.Reject(RejectReason.ALREADY_SEEN),
            ReplayGuard.admit(message(id = "m-1"), now, setOf("m-1")),
        )
    }

    @Test
    fun `a slightly fast sender is tolerated`() {
        val ahead = message(sentAt = now.plusSeconds(60))

        assertTrue(ReplayGuard.admit(ahead, now, emptySet()).isAccepted)
    }

    @Test
    fun `a wildly future-dated message is refused`() {
        val absurd = message(sentAt = now.plusSeconds(60 * 60 * 24))

        assertEquals(
            Admission.Reject(RejectReason.TOO_NEW),
            ReplayGuard.admit(absurd, now, emptySet()),
        )
    }

    @Test
    fun `the seen set stays bounded`() {
        var seen = emptyList<String>()
        repeat(ReplayGuard.REMEMBERED_IDS * 3) { seen = ReplayGuard.remember(seen, "m-$it") }

        assertEquals(ReplayGuard.REMEMBERED_IDS, seen.size)
        // The most recent are the ones kept.
        assertTrue(seen.contains("m-${ReplayGuard.REMEMBERED_IDS * 3 - 1}"))
    }

    @Test
    fun `remembering the same id twice does not duplicate it`() {
        val seen = ReplayGuard.remember(ReplayGuard.remember(emptyList(), "m-1"), "m-1")

        assertEquals(listOf("m-1"), seen)
    }
}
