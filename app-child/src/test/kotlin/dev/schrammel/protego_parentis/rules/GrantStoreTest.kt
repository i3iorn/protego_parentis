package dev.schrammel.protego_parentis.rules

import android.content.Context
import dev.schrammel.protego_parentis.model.Grant
import dev.schrammel.protego_parentis.model.GrantId
import dev.schrammel.protego_parentis.model.GrantTarget
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

/** [GrantStore.clearAll] shares [GrantStore.add]'s own lock — see its doc comment. */
@RunWith(RobolectricTestRunner::class)
class GrantStoreTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun store() = GrantStore(context)

    private fun grant(minutes: Int = 15, at: Instant = Instant.now()) = Grant(
        id = GrantId("g-$minutes-${at.toEpochMilli()}"),
        target = GrantTarget.Device,
        amount = minutes.minutes,
        grantedAt = at,
        expiresAt = at.plusSeconds(3600),
        grantedBy = "local-parent",
    )

    @Test
    fun `an added grant is active`() {
        val now = Instant.now()
        store().add(grant(at = now))

        assertTrue(store().active(now).isNotEmpty())
    }

    @Test
    fun `an expired grant is not active`() {
        val now = Instant.now()
        val expired = grant(at = now).copy(expiresAt = now.minusSeconds(1))
        store().add(expired)

        assertTrue(store().active(now).isEmpty())
    }

    @Test
    fun `clearAll drops every grant`() {
        val now = Instant.now()
        store().add(grant(at = now))

        store().clearAll()

        assertTrue(store().active(now).isEmpty())
    }

    @Test
    fun `a grant added after clearAll survives it`() {
        val now = Instant.now()
        store().add(grant(at = now))

        store().clearAll()
        store().add(grant(at = now))

        assertTrue(store().active(now).isNotEmpty())
    }
}
