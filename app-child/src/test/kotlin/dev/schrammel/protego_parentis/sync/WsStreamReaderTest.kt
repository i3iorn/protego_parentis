package dev.schrammel.protego_parentis.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * How one line of an ntfy `/json` stream becomes an envelope.
 *
 * The stream endpoint sends newline-delimited JSON, most of which is not one of
 * our messages — open and keepalive events, other people's traffic on a public
 * topic, malformed noise. Only a `"message"` event with a non-blank, plausibly
 * sized body is handed onward. These tests pin the boundary of "message we
 * recognise" without a live socket.
 */
@RunWith(RobolectricTestRunner::class)
class WsStreamReaderTest {

    private val reader = WsStreamReader("https://ntfy.sh", "topic") {}

    @Test
    fun `a message event yields its body`() {
        val line = """{"event":"message","topic":"t","message":"ciphertext","id":"x"}"""

        assertEquals("ciphertext", reader.envelopeFrom(line))
    }

    @Test
    fun `an open event is ignored`() {
        val line = """{"event":"open","topic":"t","id":"x"}"""

        assertNull(reader.envelopeFrom(line))
    }

    @Test
    fun `a keepalive event is ignored`() {
        val line = """{"event":"keepalive","topic":"t"}"""

        assertNull(reader.envelopeFrom(line))
    }

    @Test
    fun `a message event with a blank body is ignored`() {
        assertNull(reader.envelopeFrom("""{"event":"message","message":""}"""))
    }

    @Test
    fun `malformed json is ignored`() {
        assertNull(reader.envelopeFrom("not json at all"))
    }

    @Test
    fun `a blank line is ignored`() {
        assertNull(reader.envelopeFrom(""))
        assertNull(reader.envelopeFrom("   "))
    }

    @Test
    fun `an over-long line is ignored without reading it fully`() {
        val huge = "x".repeat(16_385)
        val line = """{"event":"message","message":"$huge"}"""

        assertNull(reader.envelopeFrom(line))
    }
}
