package dev.schrammel.protego_parentis.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CrashCaptureTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `nothing is recorded until an evaluation fails`() {
        assertNull(CrashCapture.lastEvaluationFailure(context))
    }

    @Test
    fun `a recorded failure's stack trace can be read back`() {
        CrashCapture.recordEvaluationFailure(context, IllegalStateException("no games after nine"))

        val recorded = CrashCapture.lastEvaluationFailure(context)
        assertTrue(recorded!!.contains("IllegalStateException"))
        assertTrue(recorded.contains("no games after nine"))
    }

    @Test
    fun `a second failure replaces the first`() {
        CrashCapture.recordEvaluationFailure(context, IllegalStateException("first"))
        CrashCapture.recordEvaluationFailure(context, IllegalStateException("second"))

        val recorded = CrashCapture.lastEvaluationFailure(context)
        assertTrue(recorded!!.contains("second"))
        assertTrue("only the most recent failure is worth keeping", "first" !in recorded)
    }

    @Test
    fun `clear removes it`() {
        CrashCapture.recordEvaluationFailure(context, IllegalStateException("boom"))

        CrashCapture.clear(context)

        assertNull(CrashCapture.lastEvaluationFailure(context))
    }

    @Test
    fun `an unreasonably long trace is capped rather than growing without bound`() {
        val hugeMessage = "x".repeat(10_000)

        CrashCapture.recordEvaluationFailure(context, IllegalStateException(hugeMessage))

        assertEquals(4000, CrashCapture.lastEvaluationFailure(context)!!.length)
    }
}
