package dev.schrammel.protego_parentis.sync

import dev.schrammel.protego_parentis.diagnostics.CrashCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The diagnostics topic the crash-report publisher derives must be the topic
 * `tools/crash_cli.py` derives from the same family code and passphrase —
 * that pairing is the whole point of the report being readable off the phone.
 * The expected names here were produced by the Python side and are pinned so
 * the two implementations cannot silently drift apart.
 */
@RunWith(RobolectricTestRunner::class)
class CrashReportPublisherTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `the derived topic matches the one the python cli derives`() {
        assertEquals(
            "dementor-DW1-test-diag-zvaq4lfqbr6v",
            diagnosticsTopic("DW1-test", "test-passphrase-123"),
        )
    }

    @Test
    fun `a different passphrase derives a different topic`() {
        val withRealPassphrase = diagnosticsTopic("DW1-test", "test-passphrase-123")
        val withWrongPassphrase = diagnosticsTopic("DW1-test", "wrong-passphrase")
        assertNotEquals(withRealPassphrase, withWrongPassphrase)
        // A wrong passphrase is fail-soft, not an error: it yields a valid
        // topic that simply has nothing on it, the same shape the CLI sees.
        assertTrue(withWrongPassphrase.startsWith("dementor-DW1-test-diag-"))
    }

    @Test
    fun `a recorded crash survives to be flushed on the next start`() {
        CrashCapture.recordUncaught(context, "main", IllegalStateException("test crash"))

        val recorded = CrashCapture.lastUncaught(context)!!
        assertEquals("main", recorded.thread)
        assertTrue(recorded.trace.contains("IllegalStateException"))
        assertTrue(recorded.trace.contains("test crash"))
        assertTrue(recorded.atMillis > 0)
    }

    @Test
    fun `report marks a crash pending for the next flush`() {
        CrashCapture.recordUncaught(context, "main", IllegalStateException("test crash"))
        val publisher = CrashReportPublisher(context) { false }
        publisher.report("main", IllegalStateException("test crash"))
        assertTrue(publisher.hasPending())
    }

    @Test
    fun `a successful flush clears the pending crash record`() {
        CrashCapture.recordUncaught(context, "main", IllegalStateException("test crash"))
        val publisher = CrashReportPublisher(context) { true }
        publisher.report("main", IllegalStateException("test crash"))
        publisher.flushPending()
        assertNull(CrashCapture.lastUncaught(context))
        assertFalse(publisher.hasPending())
    }

    @Test
    fun `a failed flush keeps the crash pending and keeps it recorded`() {
        CrashCapture.recordUncaught(context, "main", IllegalStateException("test crash"))
        val publisher = CrashReportPublisher(context) { false }
        publisher.report("main", IllegalStateException("test crash"))
        publisher.flushPending()
        assertTrue(publisher.hasPending())
        assertNotNull(CrashCapture.lastUncaught(context))
    }

    @Test
    fun `flush with nothing pending is a no-op`() {
        val publisher = CrashReportPublisher(context) { true }
        publisher.flushPending()
        assertFalse(publisher.hasPending())
        assertNull(CrashCapture.lastUncaught(context))
    }
}
