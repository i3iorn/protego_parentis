package dev.schrammel.protego_parentis.policy

import dev.schrammel.protego_parentis.model.AppRule
import dev.schrammel.protego_parentis.model.Category
import dev.schrammel.protego_parentis.model.ChildId
import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.model.Grant
import dev.schrammel.protego_parentis.model.GrantId
import dev.schrammel.protego_parentis.model.GrantTarget
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.Policy
import dev.schrammel.protego_parentis.model.RuleEffect
import dev.schrammel.protego_parentis.model.RuleTarget
import dev.schrammel.protego_parentis.model.ScheduleBlock
import dev.schrammel.protego_parentis.model.ScheduleId
import dev.schrammel.protego_parentis.model.UsageSnapshot
import dev.schrammel.protego_parentis.model.WeeklyBudget
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

/**
 * [deviceBudget] and [schedules] arrive here exactly as every test already
 * wrote them, and are translated into the equivalent [ContextRule]s rather
 * than set on [Policy] directly (#105) — [PolicyEngine] reads only rules
 * now, the same conversion [dev.schrammel.protego_parentis.rules.LocalPolicyStore.migrateToUnifiedRules]
 * does for a real device. Not shared code with that migration: this is the
 * pure-JVM test module, and duplicating a dozen lines of conversion beat
 * pulling Android into it for one function.
 */
internal fun policy(
    deviceBudget: WeeklyBudget = WeeklyBudget.UNLIMITED,
    appRules: Map<PackageName, AppRule> = emptyMap(),
    categories: List<Category> = emptyList(),
    schedules: List<ScheduleBlock> = emptyList(),
    contextRules: List<ContextRule> = emptyList(),
    safetyAllowlist: Set<PackageName> = setOf(DIALER),
    dayBoundaryHour: Int = 4,
) = Policy(
    version = 1L,
    childId = ChildId("child-1"),
    timeZone = ZONE,
    appRules = appRules,
    categories = categories,
    contextRules = contextRules + scheduleRules(schedules) + deviceBudgetRule(deviceBudget),
    safetyAllowlist = safetyAllowlist,
    dayBoundaryHour = dayBoundaryHour,
)

private fun scheduleRules(schedules: List<ScheduleBlock>): List<ContextRule> = schedules.map { schedule ->
    ContextRule(
        id = "schedule-${schedule.id.value}",
        name = schedule.name,
        enabled = true,
        condition = Condition.DuringWindow(schedule.window),
        effect = RuleEffect.Block,
        target = when (schedule.mode) {
            ScheduleBlock.Mode.INCLUSIVE -> RuleTarget.Apps(schedule.packages)
            ScheduleBlock.Mode.EXCLUSIVE -> RuleTarget.EverythingExcept(schedule.packages)
        },
    )
}

private fun deviceBudgetRule(budget: WeeklyBudget): List<ContextRule> {
    if (budget == WeeklyBudget.UNLIMITED) return emptyList()
    return listOf(
        ContextRule(
            id = "device-budget",
            name = "Device budget",
            enabled = true,
            condition = Condition.Always,
            effect = RuleEffect.SetDeviceBudget(budget),
            target = RuleTarget.Everything,
        ),
    )
}

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
    mode: ScheduleBlock.Mode = ScheduleBlock.Mode.EXCLUSIVE,
) = ScheduleBlock(
    id = ScheduleId("bedtime"),
    name = "Bedtime",
    days = setOf(weekdayOf(startingOn)),
    start = LocalTime.parse(start),
    end = LocalTime.parse(end),
    packages = allowed,
    mode = mode,
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
