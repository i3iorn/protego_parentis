package dev.schrammel.dementor_watch.policy

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Policy-day arithmetic.
 *
 * A "policy day" runs from [Policy.dayBoundaryHour][dev.schrammel.dementor_watch.model.Policy.dayBoundaryHour]
 * to the same hour the next morning, so a session at 23:50 belongs to one day
 * rather than being split across two.
 *
 * All conversion goes through the device's configured zone. The engine never
 * reads the system clock itself — [Instant] is always passed in, which is what
 * lets the reconciler substitute a server-anchored time and refuse to honour a
 * tampered device clock.
 */
object PolicyClock {

    /** The policy day that [now] falls within. */
    fun policyDay(now: Instant, zone: ZoneId, boundaryHour: Int): LocalDate =
        now.atZone(zone).minusHours(boundaryHour.toLong()).toLocalDate()

    /** The instant [day] begins. */
    fun dayStart(day: LocalDate, zone: ZoneId, boundaryHour: Int): Instant =
        day.atStartOfDay(zone).plusHours(boundaryHour.toLong()).toInstant()

    /** The instant [day] ends, which is also when its usage counters reset. */
    fun dayEnd(day: LocalDate, zone: ZoneId, boundaryHour: Int): Instant =
        dayStart(day.plusDays(1), zone, boundaryHour)
}
