package dev.schrammel.dementor_watch.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The code a parent reads aloud off one phone to check it against another.
 *
 * The whole roster design rests on that comparison actually distinguishing
 * devices and surviving a bad reading, so both properties are worth pinning
 * down directly rather than trusting them by construction.
 */
class DeviceIdentityTest {

    private val keyA = "test-key-aaaaaaaaaaaaaaaaaaaaaaaa".toByteArray()
    private val keyB = "test-key-bbbbbbbbbbbbbbbbbbbbbbbb".toByteArray()

    @Test
    fun `the same key always produces the same fingerprint`() {
        assertEquals(Fingerprints.of(keyA), Fingerprints.of(keyA))
    }

    @Test
    fun `the fingerprint is three groups of four`() {
        val id = Fingerprints.of(keyA)

        assertTrue(id.value, Regex("[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}").matches(id.value))
    }

    @Test
    fun `the fingerprint never contains the excluded letters`() {
        // The whole point of Crockford's alphabet: nothing that can be misread
        // aloud or confused with a digit.
        val id = Fingerprints.of(keyA)

        listOf('I', 'L', 'O', 'U').forEach { excluded ->
            assertTrue("fingerprint contained excluded letter $excluded: ${id.value}", excluded !in id.value)
        }
    }

    @Test
    fun `different keys produce different fingerprints`() {
        val keyC = "test-key-cccccccccccccccccccccc".toByteArray()

        val ids = setOf(Fingerprints.of(keyA), Fingerprints.of(keyB), Fingerprints.of(keyC))
        assertEquals(3, ids.size)
    }

    @Test
    fun `of base64 refuses a string that is not base64`() {
        assertNull(Fingerprints.of("not base64 at all !!!"))
    }

    @Test
    fun `of base64 agrees with of bytes for the same key`() {
        val encoded = Base64.getEncoder().encodeToString(keyA)

        assertEquals(Fingerprints.of(keyA), Fingerprints.of(encoded))
    }

    @Test
    fun `decodeKey refuses invalid base64 and empty input`() {
        assertNull(Fingerprints.decodeKey("not base64 at all !!!"))
        assertNull(Fingerprints.decodeKey(""))
    }

    @Test
    fun `encodeKey then decodeKey round trips unchanged`() {
        val decoded = Fingerprints.decodeKey(Fingerprints.encodeKey(keyA))

        assertTrue(decoded.contentEquals(keyA))
    }
}
