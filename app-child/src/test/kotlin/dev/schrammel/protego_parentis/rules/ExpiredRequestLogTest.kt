package dev.schrammel.protego_parentis.rules

import android.content.Context
import dev.schrammel.protego_parentis.model.RequestId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ExpiredRequestLogTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun log() = ExpiredRequestLog(context)

    @Test
    fun `nothing is announced before it is marked`() {
        assertFalse(log().contains(RequestId("r1")))
    }

    @Test
    fun `a marked id is remembered`() {
        log().markAnnounced(listOf(RequestId("r1"), RequestId("r2")))

        assertTrue(log().contains(RequestId("r1")))
        assertTrue(log().contains(RequestId("r2")))
        assertFalse(log().contains(RequestId("r3")))
    }

    @Test
    fun `marking again does not lose what was already there`() {
        log().markAnnounced(listOf(RequestId("r1")))
        log().markAnnounced(listOf(RequestId("r2")))

        assertTrue(log().contains(RequestId("r1")))
        assertTrue(log().contains(RequestId("r2")))
    }

    @Test
    fun `bounded like every other persisted collection in this app`() {
        val store = log()
        (1..250).forEach { store.markAnnounced(listOf(RequestId("r$it"))) }

        assertFalse("the oldest entries should have aged out", store.contains(RequestId("r1")))
        assertTrue("the newest entry must survive", store.contains(RequestId("r250")))
    }
}
