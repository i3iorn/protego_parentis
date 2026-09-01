package dev.schrammel.protego_parentis.policy.usage

import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.UsageSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class UsageAggregatesTest {

    private val game = PackageName("com.example.game")
    private val chat = PackageName("com.example.chat")

    private fun day(
        date: String,
        total: Duration,
        byPackage: Map<PackageName, Duration> = emptyMap(),
    ) = UsageSnapshot(LocalDate.parse(date), total, byPackage)

    // ---------- totals ----------

    @Test
    fun `the total is the sum of the days`() {
        val summary = UsageAggregates.summarise(
            listOf(
                day("2026-08-17", 1.hours),
                day("2026-08-18", 30.minutes),
                day("2026-08-19", 90.minutes),
            ),
        )

        assertEquals(3.hours, summary.total)
    }

    @Test
    fun `the average divides by the number of days present`() {
        val summary = UsageAggregates.summarise(
            listOf(day("2026-08-17", 1.hours), day("2026-08-18", 3.hours)),
        )

        assertEquals(2.hours, summary.dailyAverage)
    }

    @Test
    fun `a zero day still counts against the average`() {
        // Otherwise a day away from the phone would silently raise the average.
        val summary = UsageAggregates.summarise(
            listOf(
                day("2026-08-17", 2.hours),
                day("2026-08-18", Duration.ZERO),
            ),
        )

        assertEquals(1.hours, summary.dailyAverage)
    }

    @Test
    fun `an empty period has no average and no busiest day`() {
        val summary = UsageAggregates.summarise(emptyList())

        assertEquals(Duration.ZERO, summary.total)
        assertEquals(Duration.ZERO, summary.dailyAverage)
        assertNull(summary.busiestDay)
        assertTrue(summary.isEmpty)
    }

    // ---------- busiest day ----------

    @Test
    fun `the busiest day is the one with the most time`() {
        val summary = UsageAggregates.summarise(
            listOf(
                day("2026-08-17", 1.hours),
                day("2026-08-18", 4.hours),
                day("2026-08-19", 2.hours),
            ),
        )

        assertEquals(LocalDate.parse("2026-08-18"), summary.busiestDay?.policyDay)
    }

    @Test
    fun `a week of nothing has no busiest day`() {
        val summary = UsageAggregates.summarise(
            listOf(day("2026-08-17", Duration.ZERO), day("2026-08-18", Duration.ZERO)),
        )

        assertNull(summary.busiestDay)
    }

    // ---------- top apps ----------

    @Test
    fun `app time accumulates across the whole period`() {
        val summary = UsageAggregates.summarise(
            listOf(
                day("2026-08-17", 1.hours, mapOf(game to 40.minutes, chat to 20.minutes)),
                day("2026-08-18", 1.hours, mapOf(game to 30.minutes, chat to 30.minutes)),
            ),
        )

        assertEquals(
            listOf(AppTotal(game, 70.minutes), AppTotal(chat, 50.minutes)),
            summary.topApps,
        )
    }

    @Test
    fun `the top app list is capped`() {
        val days = listOf(
            day(
                "2026-08-17",
                1.hours,
                (1..10).associate { PackageName("app$it") to it.minutes },
            ),
        )

        assertEquals(3, UsageAggregates.summarise(days, topAppLimit = 3).topApps.size)
    }

    // ---------- filling gaps ----------

    @Test
    fun `missing days are filled in as zero`() {
        val filled = UsageAggregates.fill(
            days = listOf(day("2026-08-18", 1.hours)),
            from = LocalDate.parse("2026-08-17"),
            to = LocalDate.parse("2026-08-19"),
        )

        assertEquals(3, filled.size)
        assertEquals(Duration.ZERO, filled[0].deviceTotal)
        assertEquals(1.hours, filled[1].deviceTotal)
        assertEquals(Duration.ZERO, filled[2].deviceTotal)
    }

    @Test
    fun `filling returns days oldest first`() {
        val filled = UsageAggregates.fill(
            days = listOf(day("2026-08-19", 1.hours), day("2026-08-17", 2.hours)),
            from = LocalDate.parse("2026-08-17"),
            to = LocalDate.parse("2026-08-19"),
        )

        assertEquals(
            listOf(
                LocalDate.parse("2026-08-17"),
                LocalDate.parse("2026-08-18"),
                LocalDate.parse("2026-08-19"),
            ),
            filled.map { it.policyDay },
        )
    }

    @Test
    fun `filling a single day returns one day`() {
        val filled = UsageAggregates.fill(
            days = emptyList(),
            from = LocalDate.parse("2026-08-17"),
            to = LocalDate.parse("2026-08-17"),
        )

        assertEquals(1, filled.size)
    }

    @Test
    fun `days outside the range are dropped`() {
        val filled = UsageAggregates.fill(
            days = listOf(day("2026-08-01", 5.hours), day("2026-08-18", 1.hours)),
            from = LocalDate.parse("2026-08-17"),
            to = LocalDate.parse("2026-08-19"),
        )

        assertEquals(1.hours, filled.sumOf { it.deviceTotal.inWholeMinutes }.minutes)
    }

    @Test
    fun `a reversed range is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            UsageAggregates.fill(
                days = emptyList(),
                from = LocalDate.parse("2026-08-19"),
                to = LocalDate.parse("2026-08-17"),
            )
        }
    }

    // ---------- week over week ----------

    @Test
    fun `a busier week reports a positive delta`() {
        val delta = UsageAggregates.delta(
            current = listOf(day("2026-08-24", 3.hours)),
            previous = listOf(day("2026-08-17", 1.hours)),
        )

        assertEquals(2.hours, delta)
    }

    @Test
    fun `a quieter week reports a negative delta`() {
        val delta = UsageAggregates.delta(
            current = listOf(day("2026-08-24", 1.hours)),
            previous = listOf(day("2026-08-17", 4.hours)),
        )

        assertEquals(-3.hours, delta)
    }

    @Test
    fun `no previous week means the whole current week is the delta`() {
        val delta = UsageAggregates.delta(
            current = listOf(day("2026-08-24", 2.hours)),
            previous = emptyList(),
        )

        assertEquals(2.hours, delta)
    }
}
