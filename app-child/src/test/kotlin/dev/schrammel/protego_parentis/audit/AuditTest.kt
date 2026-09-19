package dev.schrammel.protego_parentis.audit

import android.content.Context
import dev.schrammel.protego_parentis.model.AuditAction
import dev.schrammel.protego_parentis.model.AuditActor
import dev.schrammel.protego_parentis.sync.ReplicatedStateStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [Audit.record] moved its read-modify-write off the caller's own thread and
 * onto a dedicated single-thread executor — see its own doc comment for why
 * (a UI tap blocking on the enforcement tick's or a sync message's unrelated
 * disk write, which read as "the UI hangs sometimes, retry works"). These pin
 * that the two properties a `synchronized` block used to give for free still
 * hold: nothing recorded is ever lost to a race, and [Audit.awaitIdle] really
 * does mean every earlier [Audit.record] has landed.
 */
@RunWith(RobolectricTestRunner::class)
class AuditTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        ReplicatedStateStore(context).forgetAll()
        context.getSharedPreferences("audit", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `awaitIdle blocks until a record made just before it has landed`() {
        Audit.record(context, AuditActor.PARENT, AuditAction.PIN_CHANGED)

        Audit.awaitIdle()

        assertTrue(Audit.entries(context).any { it.action == AuditAction.PIN_CHANGED })
    }

    @Test
    fun `entries recorded from several threads at once are none of them lost`() {
        // The exact shape the old synchronized(lock) guarded against: this
        // app records from the UI thread, the enforcement worker and the
        // sync executor, all potentially at once. A dedicated single-thread
        // executor has to preserve that guarantee despite no caller any
        // longer holding a lock itself.
        val threads = 8
        val perThread = 10
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)

        val workers = (0 until threads).map { threadIndex ->
            Thread {
                ready.countDown()
                go.await()
                repeat(perThread) { i ->
                    Audit.record(
                        context,
                        AuditActor.PARENT,
                        AuditAction.APP_LIMIT_CHANGED,
                        subject = "t$threadIndex-$i",
                        before = "0",
                        after = "1",
                    )
                }
            }.apply { start() }
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS))
        go.countDown()
        workers.forEach { it.join(5_000) }
        Audit.awaitIdle()

        assertEquals(
            threads * perThread,
            Audit.entries(context).count { it.action == AuditAction.APP_LIMIT_CHANGED },
        )
    }
}
