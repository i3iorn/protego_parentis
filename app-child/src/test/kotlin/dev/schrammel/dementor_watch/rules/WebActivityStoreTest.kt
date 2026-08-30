package dev.schrammel.dementor_watch.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [registrable] — trimming a hostname to the part worth showing a parent. */
class WebActivityStoreTest {

    @Test
    fun `a subdomain collapses to its registrable domain`() {
        assertEquals("bbc.co.uk", registrable("static.files.bbc.co.uk"))
        assertEquals("example.com", registrable("www.example.com"))
    }

    @Test
    fun `an already-bare domain is unchanged`() {
        assertEquals("example.com", registrable("example.com"))
    }

    /** A two-part suffix like .co.uk needs three labels kept, not two. */
    @Test
    fun `keeps three labels for a common second-level suffix`() {
        assertEquals("bbc.co.uk", registrable("bbc.co.uk"))
        assertEquals("bbc.co.uk", registrable("news.bbc.co.uk"))
    }

    @Test
    fun `lowercases and trims a trailing dot`() {
        assertEquals("example.com", registrable("Example.Com."))
    }

    @Test
    fun `an ip address is not a site name`() {
        assertNull(registrable("192.168.1.1"))
        assertNull(registrable("::1"))
    }

    @Test
    fun `a single label has nothing to collapse`() {
        assertNull(registrable("localhost"))
    }

    @Test
    fun `refuses blank, oversized or whitespace-containing input`() {
        assertNull(registrable(""))
        assertNull(registrable("   "))
        assertNull(registrable("a b.com"))
        assertNull(registrable("a".repeat(254) + ".com"))
    }
}
