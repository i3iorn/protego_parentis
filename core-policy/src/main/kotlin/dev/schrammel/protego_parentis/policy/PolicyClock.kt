package dev.schrammel.protego_parentis.policy

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Policy-day arithmetic.
 *
 * A "policy day" runs from [Policy.dayBoundaryHour][dev.schrammel.protego_parentis.model.Policy.dayBoundaryHour]
 * to the same hour the next morning, so a session at 23:50 belongs to one day
 * rather than being split across two.
 *
 * The boundary is a wall-clock hour, so the arithmetic is done on local
 * date-times rather than instants: on a day the clocks change, "four hours
 * after midnight" is 03:00 or 05:00, not 04:00. A boundary hour the clocks
 * skip starts the day when they resume.
 *
 * All conversion goes through the device's configured zone. The engine never
 * reads the system clock itself — [Instant] is always passed in, which is what
 * lets the reconciler substitute a server-anchored time and refuse to honour a
 * tampered device clock.
 */
object PolicyClock {

    /** The policy day that [now] falls within. */
    fun policyDay(now: Instant, zone: ZoneId, boundaryHour: Int): LocalDate =
        now.atZone(zone).toLocalDateTime().minusHours(boundaryHour.toLong()).toLocalDate()

    /** The instant [day] begins. */
    fun dayStart(day: LocalDate, zone: ZoneId, boundaryHour: Int): Instant =
        day.atTime(boundaryHour, 0).atZone(zone).toInstant()

    /** The instant [day] ends, which is also when its usage counters reset. */
    fun dayEnd(day: LocalDate, zone: ZoneId, boundaryHour: Int): Instant =
        dayStart(day.plusDays(1), zone, boundaryHour)
}
