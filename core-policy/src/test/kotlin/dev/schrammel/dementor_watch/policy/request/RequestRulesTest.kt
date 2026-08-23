package dev.schrammel.dementor_watch.policy.request

import dev.schrammel.dementor_watch.model.CategoryId
import dev.schrammel.dementor_watch.model.GrantId
import dev.schrammel.dementor_watch.model.GrantTarget
import dev.schrammel.dementor_watch.model.PackageName
import dev.schrammel.dementor_watch.model.Request
import dev.schrammel.dementor_watch.model.RequestId
import dev.schrammel.dementor_watch.model.RequestState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

class RequestRulesTest {

    private val now: Instant = Instant.parse("2026-08-22T18:00:00Z")
    private val dayStart: Instant = Instant.parse("2026-08-22T04:00:00Z")
    private val dayEnd: Instant = Instant.parse("2026-08-23T04:00:00Z")
    private val game = GrantTarget.App(PackageName("com.example.game"))

    private fun request(
        id: String = "r1",
        target: GrantTarget = GrantTarget.Device,
        state: RequestState = RequestState.PENDING,
        createdAt: Instant = now,
        expiresAt: Instant = now.plusSeconds(7200),
        decidedAt: Instant? = null,
    ) = Request(
        id = RequestId(id),
        target = target,
        amount = 15.minutes,
        reason = "Finishing a match",
        state = state,
        createdAt = createdAt,
        expiresAt = expiresAt,
        decidedAt = decidedAt,
    )

    /**
     * A request made two hours ago whose window has just closed. Its creation has
     * to be backdated too — a request cannot expire before it was made, and the
     * model enforces that.
     */
    private fun staleRequest(
        id: String = "r1",
        target: GrantTarget = GrantTarget.Device,
        state: RequestState = RequestState.PENDING,
        decidedAt: Instant? = null,
    ) = request(
        id = id,
        target = target,
        state = state,
        createdAt = now.minusSeconds(7200),
        expiresAt = now.minusSeconds(1),
        decidedAt = decidedAt,
    )

    // ---------- expiry ----------

    @Test
    fun `a request past its deadline expires`() {
        val stale = staleRequest()

        val expired = RequestRules.expire(listOf(stale), now).single()

        assertEquals(RequestState.EXPIRED, expired.state)
        assertEquals(now, expired.decidedAt)
    }

    @Test
    fun `expiry is not a denial`() {
        // Nobody said no, and the distinction matters for the rate limit below.
        val expired = RequestRules.expire(listOf(staleRequest()), now)

        assertTrue(expired.none { it.state == RequestState.DENIED })
    }

    @Test
    fun `a request still within its window is untouched`() {
        val live = request(expiresAt = now.plusSeconds(60))

        assertEquals(live, RequestRules.expire(listOf(live), now).single())
    }

    @Test
    fun `an already-decided request is not re-expired`() {
        val denied = staleRequest(
            state = RequestState.DENIED,
            decidedAt = now.minusSeconds(500),
        )

        val result = RequestRules.expire(listOf(denied), now).single()

        assertEquals(RequestState.DENIED, result.state)
        assertEquals(now.minusSeconds(500), result.decidedAt)
    }

    // ---------- submitting ----------

    @Test
    fun `a first request is allowed`() {
        assertEquals(
            SubmitDecision.Allowed,
            RequestRules.canSubmit(emptyList(), game, now, dayStart),
        )
    }

    @Test
    fun `a second request for the same thing is refused while one is pending`() {
        val pending = listOf(request(target = game))

        assertEquals(
            SubmitDecision.AlreadyPending,
            RequestRules.canSubmit(pending, game, now, dayStart),
        )
    }

    @Test
    fun `a pending request for something else does not block a new one`() {
        val pending = listOf(request(target = GrantTarget.Device))

        assertTrue(RequestRules.canSubmit(pending, game, now, dayStart).isAllowed)
    }

    @Test
    fun `an expired request no longer blocks a new one`() {
        val stale = listOf(staleRequest(target = game))

        assertTrue(RequestRules.canSubmit(stale, game, now, dayStart).isAllowed)
    }

    @Test
    fun `repeated denials for the same thing stop further asking today`() {
        val denials = (1..3).map {
            request(
                id = "r$it",
                target = game,
                state = RequestState.DENIED,
                decidedAt = now.minusSeconds(60L * it),
            )
        }

        val decision = RequestRules.canSubmit(denials, game, now, dayStart)

        assertTrue(decision is SubmitDecision.TooManyDenials)
        assertEquals(3, (decision as SubmitDecision.TooManyDenials).deniedToday)
    }

    @Test
    fun `two denials still leave one more attempt`() {
        val denials = (1..2).map {
            request(id = "r$it", target = game, state = RequestState.DENIED, decidedAt = now)
        }

        assertTrue(RequestRules.canSubmit(denials, game, now, dayStart).isAllowed)
    }

    @Test
    fun `yesterday's denials do not count against today`() {
        val yesterday = dayStart.minusSeconds(3600)
        val denials = (1..5).map {
            request(id = "r$it", target = game, state = RequestState.DENIED, decidedAt = yesterday)
        }

        assertTrue(RequestRules.canSubmit(denials, game, now, dayStart).isAllowed)
    }

    @Test
    fun `denials for a different target do not count`() {
        val denials = (1..5).map {
            request(
                id = "r$it",
                target = GrantTarget.Category(CategoryId("games")),
                state = RequestState.DENIED,
                decidedAt = now,
            )
        }

        assertTrue(RequestRules.canSubmit(denials, game, now, dayStart).isAllowed)
    }

    // ---------- approving ----------

    @Test
    fun `approval produces a matching grant`() {
        val (approved, grant) = RequestRules.approve(
            request = request(target = game),
            now = now,
            by = "parent",
            grantExpiresAt = dayEnd,
            grantId = GrantId("g1"),
        )

        assertEquals(RequestState.APPROVED, approved.state)
        assertEquals(now, approved.decidedAt)
        assertEquals("parent", approved.decidedBy)

        // The grant must match the ask exactly, or the approval is a promise the
        // device does not keep.
        assertEquals(game, grant.target)
        assertEquals(15.minutes, grant.amount)
        assertEquals(dayEnd, grant.expiresAt)
        assertEquals("r1", grant.sourceRequestId)
    }

    @Test
    fun `an approval can carry a note`() {
        val (approved, _) = RequestRules.approve(
            request = request(),
            now = now,
            by = "parent",
            grantExpiresAt = dayEnd,
            grantId = GrantId("g1"),
            note = "Last one tonight",
        )

        assertEquals("Last one tonight", approved.decisionNote)
    }

    @Test
    fun `a settled request cannot be approved again`() {
        val alreadyDenied = request(state = RequestState.DENIED, decidedAt = now)

        assertThrows(IllegalArgumentException::class.java) {
            RequestRules.approve(alreadyDenied, now, "parent", dayEnd, GrantId("g1"))
        }
    }

    // ---------- denying and withdrawing ----------

    @Test
    fun `denial records who said no and why`() {
        val denied = RequestRules.deny(request(), now, "parent", "It is a school night")

        assertEquals(RequestState.DENIED, denied.state)
        assertEquals("parent", denied.decidedBy)
        assertEquals("It is a school night", denied.decisionNote)
    }

    @Test
    fun `a child can withdraw their own request`() {
        val withdrawn = RequestRules.withdraw(request(), now)

        assertEquals(RequestState.WITHDRAWN, withdrawn.state)
        assertEquals(now, withdrawn.decidedAt)
        assertNull(withdrawn.decidedBy)
    }

    @Test
    fun `a settled request cannot be withdrawn or denied`() {
        val approved = request(state = RequestState.APPROVED, decidedAt = now)

        assertThrows(IllegalArgumentException::class.java) {
            RequestRules.withdraw(approved, now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RequestRules.deny(approved, now, "parent")
        }
    }
}
