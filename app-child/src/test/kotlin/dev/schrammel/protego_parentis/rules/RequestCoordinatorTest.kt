package dev.schrammel.protego_parentis.rules

import android.content.Context
import dev.schrammel.protego_parentis.audit.Audit
import dev.schrammel.protego_parentis.model.AuditAction
import dev.schrammel.protego_parentis.model.GrantTarget
import dev.schrammel.protego_parentis.model.Request
import dev.schrammel.protego_parentis.model.RequestId
import dev.schrammel.protego_parentis.model.RequestState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

/**
 * [RequestCoordinator.notifyLapsedRequests] is the one caller for whom a
 * lazily-computed expiry (see [RequestStore.all]'s own doc comment) needs to
 * become a discrete, announced-once event — see that method's own doc
 * comment for why it keeps its own record of what it has already announced.
 */
@RunWith(RobolectricTestRunner::class)
class RequestCoordinatorTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun coordinator() = RequestCoordinator(context)

    private fun lapsedPendingRequest(now: Instant): Request = Request(
        id = RequestId("lapsed-1"),
        target = GrantTarget.Device,
        amount = 15.minutes,
        reason = "homework",
        state = RequestState.PENDING,
        createdAt = now.minusSeconds(3 * 60 * 60),
        expiresAt = now.minusSeconds(60 * 60),
    )

    @Test
    fun `nothing is announced when nothing has lapsed`() {
        val now = Instant.now()
        coordinator().submit(GrantTarget.Device, 10.minutes, "homework", now)

        coordinator().notifyLapsedRequests(now)
        Audit.awaitIdle()

        assertTrue(Audit.entries(context).none { it.action == AuditAction.REQUEST_EXPIRED })
    }

    @Test
    fun `a request past its deadline is recorded as expired`() {
        val now = Instant.now()
        RequestStore(context).save(lapsedPendingRequest(now))

        coordinator().notifyLapsedRequests(now)
        Audit.awaitIdle()

        assertTrue(Audit.entries(context).any { it.action == AuditAction.REQUEST_EXPIRED })
    }

    @Test
    fun `the same lapsed request is never announced twice`() {
        val now = Instant.now()
        RequestStore(context).save(lapsedPendingRequest(now))

        coordinator().notifyLapsedRequests(now)
        coordinator().notifyLapsedRequests(now.plusSeconds(60))
        Audit.awaitIdle()

        assertEquals(1, Audit.entries(context).count { it.action == AuditAction.REQUEST_EXPIRED })
    }
}
