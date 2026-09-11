package dev.schrammel.protego_parentis.sync.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The code one person reads out to another.
 *
 * It is typed in by hand, so the failure that matters is a single wrong
 * character: without a checksum that reaches a topic nobody is publishing to and
 * fails by staying silent forever, which is the worst possible way for a setup
 * step to go wrong.
 */
class FamilyTokensTest {

    private val token = FamilyToken(
        kind = TransportKind.NTFY,
        serverUrl = "https://ntfy.sh",
        topic = "dementor-1a2b3c4d5e",
    )

    @Test
    fun `round-trips`() {
        assertEquals(token, FamilyTokens.decode(FamilyTokens.encode(token)))
    }

    @Test
    fun `round-trips a self-hosted server`() {
        val own = token.copy(serverUrl = "https://ntfy.example.co.uk:8443")

        assertEquals(own, FamilyTokens.decode(FamilyTokens.encode(own)))
    }

    /** People type these while reading them off another screen. */
    @Test
    fun `tolerates case and spacing`() {
        val code = FamilyTokens.encode(token)

        assertEquals(token, FamilyTokens.decode(code.lowercase()))
        assertEquals(token, FamilyTokens.decode("  $code  "))
        assertEquals(token, FamilyTokens.decode(code.replace("-", "- ")))
    }

    @Test
    fun `refuses a mistyped code`() {
        val code = FamilyTokens.encode(token)
        val body = code.split('-')[1]
        // One character wrong, in the payload rather than the checksum.
        val broken = code.replace(body, body.dropLast(1) + if (body.last() == '7') '8' else '7')

        assertNotEquals(code, broken)
        assertNull(FamilyTokens.decode(broken))
    }

    @Test
    fun `refuses anything that is not one of ours`() {
        listOf(
            "",
            "hello",
            "DW1",
            "DW1-",
            "DW2-ABCD-EFGH",
            "https://ntfy.sh/dementor-123",
        ).forEach { value -> assertNull(value, FamilyTokens.decode(value)) }
    }

    /**
     * A code naming a backend this build has never heard of is refused rather
     * than guessed at: silently falling back to ntfy would send a family's
     * traffic somewhere they did not choose.
     *
     * Asserted on the lookup the decoder depends on, because a code carrying an
     * unknown backend cannot be built here — encode() only takes a kind that
     * exists.
     */
    @Test
    fun `refuses a backend it has never heard of`() {
        assertNull(TransportKind.parse("carrier-pigeon"))
        assertNull(TransportKind.parse(""))
        assertNull(TransportKind.parse(null))
        // And the one it does know, however it was cased in transit.
        assertEquals(TransportKind.NTFY, TransportKind.parse("ntfy"))
    }

    @Test
    fun `refuses a code with the wrong prefix`() {
        val code = FamilyTokens.encode(token)

        assertNull(FamilyTokens.decode(code.replaceFirst("DW1", "DW9")))
    }

    /**
     * The topic goes into a URL path and a query string, so what it may contain
     * is a security question rather than a formatting one.
     */
    @Test
    fun `refuses a topic that would change the URL`() {
        listOf("family/../admin", "family?poll=1", "family#x", "a b", "a".repeat(65), "")
            .forEach { topic -> assertFalse(topic, FamilyToken.isValidTopic(topic)) }

        listOf("dementor-1a2b", "family_topic", "ABC123", "a".repeat(64))
            .forEach { topic -> assertTrue(topic, FamilyToken.isValidTopic(topic)) }
    }

    @Test
    fun `refuses a token that could never be reached`() {
        listOf(
            { FamilyToken(TransportKind.NTFY, "http://ntfy.sh", "topic") },
            { FamilyToken(TransportKind.NTFY, "https://ntfy.sh", " ") },
            { FamilyToken(TransportKind.NTFY, "https://ntfy.sh", "has/slash") },
        ).forEach { build ->
            try {
                build()
                throw AssertionError("expected an unusable token to be refused")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        }
    }
}
