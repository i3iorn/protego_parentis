package dev.schrammel.dementor_watch.rules

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [isPlausibleHost] — catching a pasted URL where a hostname was expected. */
class WebFilterStoreTest {

    @Test
    fun `a plain hostname is plausible`() {
        assertTrue(isPlausibleHost("example.com"))
    }

    @Test
    fun `surrounding whitespace is trimmed before checking`() {
        assertTrue(isPlausibleHost("  example.com  "))
    }

    @Test
    fun `refuses blank input`() {
        assertFalse(isPlausibleHost(""))
        assertFalse(isPlausibleHost("   "))
    }

    @Test
    fun `refuses a bare word with no dot`() {
        assertFalse(isPlausibleHost("localhost"))
    }

    @Test
    fun `refuses a pasted url rather than a hostname`() {
        assertFalse(isPlausibleHost("https://example.com"))
        assertFalse(isPlausibleHost("example.com/path"))
    }

    @Test
    fun `refuses a value containing whitespace`() {
        assertFalse(isPlausibleHost("example .com"))
    }

    @Test
    fun `refuses a host with a port`() {
        assertFalse(isPlausibleHost("example.com:8080"))
    }

    @Test
    fun `refuses an oversized value`() {
        assertFalse(isPlausibleHost("a".repeat(254) + ".com"))
    }
}
