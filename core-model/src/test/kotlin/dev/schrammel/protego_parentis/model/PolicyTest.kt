package dev.schrammel.protego_parentis.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

private val DIALER = PackageName("com.android.dialer")
private val GAME = PackageName("com.example.game")
private val CHAT = PackageName("com.example.chat")

private fun policy(
    appRules: Map<PackageName, AppRule> = emptyMap(),
    categories: List<Category> = emptyList(),
    schedules: List<ScheduleBlock> = emptyList(),
    safetyAllowlist: Set<PackageName> = emptySet(),
    dayBoundaryHour: Int = 4,
) = Policy(
    version = 1L,
    childId = ChildId("child"),
    timeZone = ZoneId.of("UTC"),
    appRules = appRules,
    categories = categories,
    schedules = schedules,
    safetyAllowlist = safetyAllowlist,
    dayBoundaryHour = dayBoundaryHour,
)

private fun schedule(id: String) = ScheduleBlock(
    id = ScheduleId(id),
    name = "Bedtime",
    days = setOf(DayOfWeek.MONDAY),
    start = LocalTime.of(22, 0),
    end = LocalTime.of(7, 0),
)

class PolicyTest {

    // ---------- validation ----------

    @Test
    fun `an out-of-range day boundary is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { policy(dayBoundaryHour = 24) }
        assertThrows(IllegalArgumentException::class.java) { policy(dayBoundaryHour = -1) }
    }

    @Test
    fun `duplicate schedule ids are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            policy(schedules = listOf(schedule("bedtime"), schedule("bedtime")))
        }
    }

    @Test
    fun `duplicate category ids are rejected`() {
        val one = Category(CategoryId("games"), "Games", WeeklyBudget.UNLIMITED)
        val two = Category(CategoryId("games"), "Also games", WeeklyBudget.UNLIMITED)

        assertThrows(IllegalArgumentException::class.java) {
            policy(categories = listOf(one, two))
        }
    }

    // ---------- lookups ----------

    @Test
    fun `an app with no rule is simply allowed`() {
        assertEquals(AppRule.Allowed, policy().ruleFor(GAME))
    }

    @Test
    fun `safety exemption comes from the allowlist or from the rule`() {
        val fromList = policy(safetyAllowlist = setOf(DIALER))
        val fromRule = policy(appRules = mapOf(DIALER to AppRule.AlwaysAllowed))

        assertTrue(fromList.isSafetyExempt(DIALER))
        assertTrue(fromRule.isSafetyExempt(DIALER))
        assertFalse(fromList.isSafetyExempt(GAME))
    }

    @Test
    fun `a budgeted app is not safety exempt`() {
        val subject = policy(appRules = mapOf(GAME to AppRule.Budgeted(WeeklyBudget.flat(1.hours))))

        assertFalse(subject.isSafetyExempt(GAME))
    }

    @Test
    fun `a category reports its own members`() {
        // Nested membership is resolved by CategoryHierarchy, which is tested
        // separately; this covers only direct membership.
        val games = Category(CategoryId("games"), "Games", WeeklyBudget.UNLIMITED, setOf(GAME))

        assertTrue(games.contains(GAME))
        assertFalse(games.contains(CHAT))
    }
}

class CategoryTest {

    @Test
    fun `a category needs a name`() {
        assertThrows(IllegalArgumentException::class.java) {
            Category(CategoryId("games"), "   ", WeeklyBudget.UNLIMITED)
        }
    }

    @Test
    fun `membership is by package`() {
        val category = Category(CategoryId("games"), "Games", WeeklyBudget.UNLIMITED, setOf(GAME))

        assertTrue(category.contains(GAME))
        assertFalse(category.contains(CHAT))
    }
}

class ScheduleBlockTest {

    @Test
    fun `a window ending before it starts crosses midnight`() {
        assertTrue(schedule("bedtime").crossesMidnight)
    }

    @Test
    fun `a same-day window does not cross midnight`() {
        val block = ScheduleBlock(
            id = ScheduleId("school"),
            name = "School",
            days = setOf(DayOfWeek.MONDAY),
            start = LocalTime.of(8, 0),
            end = LocalTime.of(15, 0),
        )

        assertFalse(block.crossesMidnight)
    }

    @Test
    fun `a schedule needs a name, some days, and a non-empty window`() {
        assertThrows(IllegalArgumentException::class.java) {
            schedule("x").copy(name = " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            schedule("x").copy(days = emptySet())
        }
        assertThrows(IllegalArgumentException::class.java) {
            // Identical start and end would mean either nothing or everything.
            schedule("x").copy(start = LocalTime.of(9, 0), end = LocalTime.of(9, 0))
        }
    }
}

class GrantTest {

    private val now: Instant = Instant.parse("2026-08-21T18:00:00Z")

    private fun grant(
        amount: Duration = 15.minutes,
        from: Instant = now,
        until: Instant = now.plusSeconds(3600),
    ) = Grant(
        id = GrantId("g1"),
        target = GrantTarget.Device,
        amount = amount,
        grantedAt = from,
        expiresAt = until,
        grantedBy = "parent",
    )

    @Test
    fun `a grant is active between issue and expiry`() {
        val subject = grant()

        assertTrue(subject.isActiveAt(now))
        assertTrue(subject.isActiveAt(now.plusSeconds(1800)))
    }

    @Test
    fun `a grant is inactive at and after expiry`() {
        val subject = grant()

        assertFalse(subject.isActiveAt(now.plusSeconds(3600)))
        assertFalse(subject.isActiveAt(now.plusSeconds(7200)))
    }

    @Test
    fun `a grant is inactive before it was issued`() {
        val subject = grant()

        assertFalse(subject.isActiveAt(now.minusSeconds(1)))
    }

    @Test
    fun `a grant must be positive and must expire after it is issued`() {
        assertThrows(IllegalArgumentException::class.java) { grant(amount = Duration.ZERO) }
        assertThrows(IllegalArgumentException::class.java) { grant(amount = (-5).minutes) }
        assertThrows(IllegalArgumentException::class.java) { grant(until = now) }
    }

    @Test
    fun `targets distinguish device, app, and category`() {
        assertEquals(GrantTarget.Device, GrantTarget.Device)
        assertEquals(GrantTarget.App(GAME), GrantTarget.App(GAME))
        assertFalse(GrantTarget.App(GAME) == GrantTarget.App(CHAT))
        assertEquals(GrantTarget.Category(CategoryId("g")), GrantTarget.Category(CategoryId("g")))
    }
}

class UsageSnapshotTest {

    @Test
    fun `an unseen package has no time`() {
        val snapshot = UsageSnapshot.empty(java.time.LocalDate.parse("2026-08-21"))

        assertEquals(Duration.ZERO, snapshot.forPackage(GAME))
        assertEquals(Duration.ZERO, snapshot.deviceTotal)
    }

    @Test
    fun `device total is tracked separately from the per-app sum`() {
        // They legitimately disagree: exempt apps count in byPackage but not
        // against the device budget.
        val snapshot = UsageSnapshot(
            policyDay = java.time.LocalDate.parse("2026-08-21"),
            deviceTotal = 30.minutes,
            byPackage = mapOf(GAME to 30.minutes, DIALER to 20.minutes),
        )

        assertEquals(30.minutes, snapshot.deviceTotal)
        assertEquals(20.minutes, snapshot.forPackage(DIALER))
    }
}

class DeviceStateTest {

    @Test
    fun `the navigating app is the one whose suspension is deferred`() {
        val maps = PackageName("com.example.maps")

        assertEquals(maps, DeviceState(navigatingPackage = maps).suspensionDeferredFor)
        assertNull(DeviceState.IDLE.suspensionDeferredFor)
    }

    /** Being in the foreground is not navigating; only a live route is. */
    @Test
    fun `the foreground app alone defers nothing`() {
        val maps = PackageName("com.example.maps")

        assertNull(DeviceState(foregroundPackage = maps).suspensionDeferredFor)
    }
}
