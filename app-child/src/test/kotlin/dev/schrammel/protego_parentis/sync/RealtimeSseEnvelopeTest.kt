package dev.schrammel.protego_parentis.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RealtimeSseEnvelopeTest {

    @Test
    fun `reads the envelope out of a message event`() {
        val line = """data: {"event":"message","message":"sealed-envelope-text"}"""
        assertEquals("sealed-envelope-text", RealtimeSseEnvelope.parse(line))
    }

    @Test
    fun `ignores keepalive and open events`() {
        assertNull(RealtimeSseEnvelope.parse("""data: {"event":"keepalive"}"""))
        assertNull(RealtimeSseEnvelope.parse("""data: {"event":"open"}"""))
    }

    @Test
    fun `ignores lines that are not a data line`() {
        assertNull(RealtimeSseEnvelope.parse(""))
        assertNull(RealtimeSseEnvelope.parse("event: message"))
        assertNull(RealtimeSseEnvelope.parse(": comment"))
    }

    @Test
    fun `ignores malformed json`() {
        assertNull(RealtimeSseEnvelope.parse("data: not json"))
    }

    @Test
    fun `ignores a message event with a blank body`() {
        assertNull(RealtimeSseEnvelope.parse("""data: {"event":"message","message":""}"""))
        assertNull(RealtimeSseEnvelope.parse("""data: {"event":"message"}"""))
    }

    @Test
    fun `ignores a line far longer than any real message`() {
        val huge = "data: " + "x".repeat(20_000)
        assertNull(RealtimeSseEnvelope.parse(huge))
    }
}
