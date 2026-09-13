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
    fun `a line the size of a large real app inventory is not dropped`() {
        // Comfortably past the old 16,384-character bound this used to fail
        // against — see RealtimeSseEnvelope.MAX_LINE_CHARS's doc comment.
        val bigEnvelope = "x".repeat(40_000)
        val line = """data: {"event":"message","message":"$bigEnvelope"}"""

        assertEquals(bigEnvelope, RealtimeSseEnvelope.parse(line))
    }

    @Test
    fun `ignores a line past the new bound rather than pulling it fully into memory`() {
        val huge = "data: " + "x".repeat(RealtimeSseEnvelope.MAX_LINE_CHARS + 1)
        assertNull(RealtimeSseEnvelope.parse(huge))
    }
}
