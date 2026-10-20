package dev.schrammel.protego_parentis.audit

import android.content.Context
import dev.schrammel.protego_parentis.model.AuditAction
import dev.schrammel.protego_parentis.model.AuditActor
import dev.schrammel.protego_parentis.model.AuditEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class ActivitySeenTrackerTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        // The tracker persists its baseline in SharedPreferences, and
        // Robolectric hands every test method a fresh application but the same
        // pref file — so without this, a baseline written by an earlier method
        // (seconds earlier, by wall-clock) leaks into this one and "unseen"
        // counts an entry the previous method created.
        context.getSharedPreferences("activity_seen", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun tracker() = ActivitySeenTracker(context)

    private fun entry(at: Instant) = AuditEntry(
        id = at.toString(),
        at = at,
        actor = AuditActor.SYSTEM,
        action = AuditAction.BONUS_GRANTED,
    )

    @Test
    fun `the first ever check treats existing history as background, not a backlog`() {
        val old = listOf(entry(Instant.now().minusSeconds(60)), entry(Instant.now().minusSeconds(30)))

        val unseen = tracker().unseen(old)

        assertTrue("a family with months of history must not see it all as new", unseen.isEmpty())
    }

    @Test
    fun `only entries after the last-seen moment count once a baseline exists`() {
        val t = tracker()
        val first = entry(Instant.now().minusSeconds(60))
        t.unseen(listOf(first)) // establishes the baseline at `first`'s moment

        val second = entry(Instant.now())
        val unseen = t.unseen(listOf(first, second))

        assertEquals(listOf(second), unseen)
    }

    @Test
    fun `marking seen clears what was unseen`() {
        val t = tracker()
        val old = entry(Instant.now().minusSeconds(60))
        t.unseen(listOf(old)) // baseline

        val newer = entry(Instant.now())
        assertEquals(listOf(newer), t.unseen(listOf(old, newer)))

        t.markSeen(listOf(old, newer))

        assertTrue(t.unseen(listOf(old, newer)).isEmpty())
    }
}
