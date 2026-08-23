package dev.schrammel.dementor_watch.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The topic is typed by a person and then interpolated into a URL path and a
 * query string, so what it may contain is a security question rather than a
 * formatting one.
 */
class TopicValidationTest {

    @Test
    fun `accepts what ntfy accepts`() {
        assertTrue(isValidTopic("dementor-1a2b3c"))
        assertTrue(isValidTopic("family_topic"))
        assertTrue(isValidTopic("ABC123"))
    }

    @Test
    fun `trims before judging`() {
        assertTrue(isValidTopic("  dementor-1a2b  "))
    }

    /** A slash would reach a different path on the server entirely. */
    @Test
    fun `refuses anything that would change the URL`() {
        assertFalse(isValidTopic("family/../admin"))
        assertFalse(isValidTopic("family?poll=1"))
        assertFalse(isValidTopic("family#fragment"))
        assertFalse(isValidTopic("family topic"))
        assertFalse(isValidTopic("family@host"))
        assertFalse(isValidTopic("family\nsecond"))
    }

    @Test
    fun `refuses an empty or over-long topic`() {
        assertFalse(isValidTopic(""))
        assertFalse(isValidTopic("   "))
        assertFalse(isValidTopic("a".repeat(65)))
        assertTrue(isValidTopic("a".repeat(64)))
    }
}
