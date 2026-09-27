package dev.schrammel.protego_parentis.glucose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NightscoutUrlTest {

    @Test
    fun `an https address is cleaned up to a base`() {
        assertEquals("https://kid.example.org", NightscoutUrl.normalise(" https://Kid.Example.org/ "))
        assertEquals("https://kid.example.org", NightscoutUrl.normalise("https://kid.example.org/api/v1/"))
        assertEquals("https://ns.example.org:8443/sub", NightscoutUrl.normalise("https://ns.example.org:8443/sub/"))
    }

    @Test
    fun `cleartext, credentials, queries and non-urls are refused`() {
        assertNull(NightscoutUrl.normalise("http://kid.example.org"))
        assertNull(NightscoutUrl.normalise("https://token@kid.example.org"))
        assertNull(NightscoutUrl.normalise("https://kid.example.org/?token=abc"))
        assertNull(NightscoutUrl.normalise("kid.example.org"))
        assertNull(NightscoutUrl.normalise(""))
    }

    @Test
    fun `a token with spaces in it was pasted with something else`() {
        assertTrue(NightscoutUrl.isPlausibleToken("reader-1a2b3c4d5e6f7a8b"))
        assertFalse(NightscoutUrl.isPlausibleToken("reader 1a2b"))
        assertFalse(NightscoutUrl.isPlausibleToken(" "))
    }
}
