package dev.schrammel.protego_parentis.rules

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate

/** [GoalStore.celebratedDays] is bounded (#156) — it must not grow forever. */
@RunWith(RobolectricTestRunner::class)
class GoalStoreTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun store() = GoalStore(context)

    @Test
    fun `celebrating a day adds it`() {
        val store = store()
        val day = LocalDate.of(2026, 9, 1)

        store.markCelebrated(day)

        assertEquals(setOf(day), store.celebratedDays())
    }

    @Test
    fun `celebrated days are capped at 100, keeping the most recent`() {
        val store = store()
        val start = LocalDate.of(2020, 1, 1)

        (0 until 110).forEach { offset -> store.markCelebrated(start.plusDays(offset.toLong())) }

        val kept = store.celebratedDays()
        assertEquals(100, kept.size)
        assertTrue("the oldest days must be the ones dropped", start !in kept)
        assertTrue(start.plusDays(109) in kept)
    }
}
