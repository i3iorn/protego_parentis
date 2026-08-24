package dev.schrammel.dementor_watch.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Both ends of this protocol talk to something unauthenticated: anything on the
 * network can open the port, and anything can answer on it. So the tests that
 * matter are the ones about rubbish rather than the ones about a good exchange.
 */
class PairingWireTest {

    private val token = FamilyToken(
        kind = TransportKind.NTFY,
        serverUrl = "https://ntfy.sh",
        topic = "dw-abc123",
    )

    // ---------- the exchange that works ----------

    @Test
    fun `a hello survives the round trip`() {
        val code = PairingWire.code()
        assertEquals(code, PairingWire.readHello(PairingWire.hello(code)))
    }

    @Test
    fun `a granted token survives the round trip`() {
        val answer = PairingWire.readAnswer(PairingWire.grant(token))
        assertEquals(PairingWire.Answer.Granted(token), answer)
    }

    @Test
    fun `a refusal is understood as one`() {
        assertEquals(PairingWire.Answer.Denied, PairingWire.readAnswer(PairingWire.deny()))
    }

    @Test
    fun `a code is the advertised shape`() {
        repeat(50) {
            val code = PairingWire.code()
            assertEquals(PairingWire.CODE_LENGTH, code.length)
            assertTrue("not digits: $code", code.all { it in '0'..'9' })
        }
    }

    // ---------- everything else on the port ----------

    @Test
    fun `anything that is not a hello is not one`() {
        listOf(
            "",
            "   ",
            "GET / HTTP/1.1",
            "DWP1",
            "DWP1 HELLO",
            "DWP1 HELLO abcdef",
            "DWP1 HELLO 12345",
            "DWP1 HELLO 1234567",
            "DWP1 GOODBYE 123456",
            "DWP2 HELLO 123456",
            "DWP1 HELLO 123456 extra",
        ).forEach { line ->
            assertNull("accepted: '$line'", PairingWire.readHello(line))
        }
    }

    @Test
    fun `anything that is not an answer is unreadable`() {
        listOf(
            "",
            "OK",
            "DWP1",
            "DWP2 TOKEN whatever",
            "DWP1 TOKEN",
            "DWP1 TOKEN not-a-token",
            "DWP1 DENY extra",
            "DWP1 SOMETHING else",
        ).forEach { line ->
            assertEquals(
                "accepted: '$line'",
                PairingWire.Answer.Unreadable,
                PairingWire.readAnswer(line),
            )
        }
    }

    @Test
    fun `a token that does not decode is not a family`() {
        // The answer arrives over an unauthenticated socket, so the token in it is
        // a claim rather than a fact. Everything that makes it trustworthy —
        // checksum, a transport this build knows, and the rule that a family
        // server must be https — lives in FamilyTokens.decode and is tested in
        // FamilyTokensTest. What this pins is that PairingWire actually asks:
        // a string that fails any of those must arrive as Unreadable, never as a
        // family this phone then joins.
        listOf(
            "DW1-AAAA-BBBB",
            "DW1--",
            FamilyTokens.encode(token).dropLast(1),
        ).forEach { claimed ->
            assertEquals(
                "accepted: '$claimed'",
                PairingWire.Answer.Unreadable,
                PairingWire.readAnswer("DWP1 TOKEN $claimed"),
            )
        }
    }

    @Test
    fun `surrounding whitespace does not change the meaning`() {
        // Lines arrive off a socket reader; a stray carriage return should not
        // turn a grant into an unreadable answer.
        val granted = PairingWire.readAnswer("  " + PairingWire.grant(token) + "\r\n")
        assertEquals(PairingWire.Answer.Granted(token), granted)
    }

    @Test
    fun `the offer cannot be left standing`() {
        // Pinned because the whole design rests on the advertisement being
        // temporary. A build that quietly raised this to an hour would be
        // advertising a household to its network for an hour.
        assertTrue(PairingWire.OFFER_SECONDS in 30..300)
    }
}
