package dev.schrammel.protego_parentis.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [Diagnostics.raw]/[Diagnostics.asText] (the [String] overload) exist so a
 * copy of this phone's own log can be carried elsewhere — a parent's phone,
 * over sync — and rendered there exactly as [Diagnostics.asText] (the
 * [android.content.Context] overload) already renders it here. These pin
 * that the two paths never disagree.
 */
@RunWith(RobolectricTestRunner::class)
class DiagnosticsTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `raw is empty before anything is recorded`() {
        assertEquals("", Diagnostics.raw(context))
    }

    @Test
    fun `raw carries exactly what asText would render locally`() {
        Diagnostics.record(context, DiagnosticEvent.SERVICE_STARTED)
        Diagnostics.record(context, DiagnosticEvent.EVALUATED, mapOf("apps" to 3L))

        val local = Diagnostics.asText(context, header = "Header", empty = "Empty")
        val carried = Diagnostics.asText(Diagnostics.raw(context), header = "Header", empty = "Empty")

        assertEquals(local, carried)
    }

    @Test
    fun `a carried copy renders the empty message just like a local one would`() {
        assertEquals(
            Diagnostics.asText(context, header = "Header", empty = "Nothing yet"),
            Diagnostics.asText(Diagnostics.raw(context), header = "Header", empty = "Nothing yet"),
        )
    }

    @Test
    fun `a malformed carried copy renders whatever it can rather than throwing`() {
        val goodLine = DiagnosticLogFormat.encode(DiagnosticEntry(java.time.Instant.EPOCH, DiagnosticEvent.SERVICE_STARTED))
        val rendered = Diagnostics.asText("not a real log\n$goodLine", header = "Header", empty = "Empty")

        assertTrue(rendered.contains("SERVICE_STARTED"))
    }

    /**
     * The in-memory log is keyed on the stored string, not held
     * unconditionally: Robolectric gives each test a fresh preference file in
     * the same process, and an unkeyed cache served the previous test's
     * entries to the next.
     */
    @Test
    fun `a log replaced underneath the cache is read afresh`() {
        Diagnostics.record(context, DiagnosticEvent.SERVICE_STARTED)
        assertEquals(1, Diagnostics.entries(context).size)

        context.getSharedPreferences("diagnostics", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()

        assertEquals(emptyList<DiagnosticEntry>(), Diagnostics.entries(context))
    }
}
