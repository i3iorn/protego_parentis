package dev.schrammel.protego_parentis.rules

import android.content.Context
import dev.schrammel.protego_parentis.model.GoalAchievement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes

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

    @Test
    fun `a pending achievement can be read back`() {
        val store = store()
        val achievement = GoalAchievement(
            day = LocalDate.of(2026, 9, 1),
            used = 30.minutes,
            target = 45.minutes,
            prize = "Extra story tonight",
        )

        store.setPending(achievement)

        assertEquals(achievement, store.pending())
    }

    @Test
    fun `marking a day celebrated clears the pending achievement and adds the day`() {
        val store = store()
        val day = LocalDate.of(2026, 9, 1)
        store.setPending(GoalAchievement(day, used = 30.minutes, target = 45.minutes, prize = "Prize"))

        store.markCelebrated(day)

        assertNull(store.pending())
        assertTrue(day in store.celebratedDays())
    }
}
