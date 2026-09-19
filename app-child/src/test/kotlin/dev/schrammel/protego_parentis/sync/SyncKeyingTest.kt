package dev.schrammel.protego_parentis.sync

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [SyncKeying.execute] is the one thread every publish, poll and realtime
 * envelope in this app is serialized through — see its own doc comment. A
 * task that throws (a handler dispatching a malformed-but-signed message,
 * say) used to run uncaught on that thread, which is fatal to the whole
 * process, not just this one task: Android's default uncaught-exception
 * handler tears the app down for an exception on any thread. This pins that
 * one task's own bug is now contained, and the shared thread keeps serving
 * whatever is queued behind it.
 */
@RunWith(RobolectricTestRunner::class)
class SyncKeyingTest {

    @Test
    fun `a task that throws does not stop later tasks from running`() {
        val ran = CountDownLatch(1)

        SyncKeying.execute { throw IllegalStateException("simulated malformed message") }
        SyncKeying.execute { ran.countDown() }

        assertTrue(ran.await(5, TimeUnit.SECONDS))
    }
}
