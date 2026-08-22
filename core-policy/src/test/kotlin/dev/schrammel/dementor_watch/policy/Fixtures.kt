package dev.schrammel.dementor_watch.policy

import dev.schrammel.dementor_watch.model.AppRule
import dev.schrammel.dementor_watch.model.Category
import dev.schrammel.dementor_watch.model.ChildId
import dev.schrammel.dementor_watch.model.Grant
import dev.schrammel.dementor_watch.model.GrantId
import dev.schrammel.dementor_watch.model.GrantTarget
import dev.schrammel.dementor_watch.model.PackageName
import dev.schrammel.dementor_watch.model.Policy
import dev.schrammel.dementor_watch.model.ScheduleBlock
import dev.schrammel.dementor_watch.model.ScheduleId
import dev.schrammel.dementor_watch.model.UsageSnapshot
import dev.schrammel.dementor_watch.model.WeeklyBudget
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.time.Duration

internal val ZONE: ZoneId = ZoneId.of("Europe/Vienna")

internal val DIALER = PackageName("com.android.dialer")
internal val CLOCK = PackageName("com.google.android.deskclock")
internal val YOUTUBE = PackageName("com.google.android.youtube")
internal val GAME = PackageName("com.supercell.clashroyale")
internal val HOMEWORK = PackageName("com.google.android.apps.docs")

internal val ALL_PACKAGES = setOf(DIALER, CLOCK, YOUTUBE, GAME, HOMEWORK)

/** An instant in the test zone, e.g. `at("2026-08-22", "02:00")`. */
internal fun at(date: String, time: String): Instant =
    ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), ZONE).toInstant()

internal fun zdt(date: String, time: String): ZonedDateTime =
    ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), ZONE)

/** The weekday a date falls on, so tests never hard-code a day-of-week by hand. */
internal fun weekdayOf(date: String) = LocalDate.parse(date).dayOfWeek

internal fun policy(
    deviceBudget: WeeklyBudget = WeeklyBudget.UNLIMITED,
    appRules: Map<PackageName, AppRule> = emptyMap(),
    categories: List<Category> = emptyList(),
    schedules: List<ScheduleBlock> = emptyList(),
    safetyAllowlist: Set<PackageName> = setOf(DIALER),
    dayBoundaryHour: Int = 4,
) = Policy(
    version = 1L,
    childId = ChildId("child-1"),
    timeZone = ZONE,
    deviceBudget = deviceBudget,
    appRules = appRules,
    categories = categories,
    schedules = schedules,
    safetyAllowlist = safetyAllowlist,
    dayBoundaryHour = dayBoundaryHour,
)

internal fun usage(
    day: String,
    deviceTotal: Duration = Duration.ZERO,
    byPackage: Map<PackageName, Duration> = emptyMap(),
) = UsageSnapshot(LocalDate.parse(day), deviceTotal, byPackage)

internal fun bedtime(
    startingOn: String,
    start: String = "22:30",
    end: String = "07:00",
    allowed: Set<PackageName> = setOf(CLOCK),
) = ScheduleBlock(
    id = ScheduleId("bedtime"),
    name = "Bedtime",
    days = setOf(weekdayOf(startingOn)),
    start = LocalTime.parse(start),
    end = LocalTime.parse(end),
    allowedPackages = allowed,
    kind = ScheduleBlock.Kind.BEDTIME,
)

internal fun grant(
    target: GrantTarget,
    amount: Duration,
    from: Instant,
    until: Instant,
) = Grant(
    id = GrantId("grant-1"),
    target = target,
    amount = amount,
    grantedAt = from,
    expiresAt = until,
    grantedBy = "parent-1",
)
