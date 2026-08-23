package dev.schrammel.dementor_watch.policy

import dev.schrammel.dementor_watch.model.AppRule
import dev.schrammel.dementor_watch.model.BudgetLevel
import dev.schrammel.dementor_watch.model.Category
import dev.schrammel.dementor_watch.model.CategoryId
import dev.schrammel.dementor_watch.model.DeviceState
import dev.schrammel.dementor_watch.model.GrantTarget
import dev.schrammel.dementor_watch.model.PackageName
import dev.schrammel.dementor_watch.model.Reason
import dev.schrammel.dementor_watch.model.WeeklyBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class CategoryBudgetTest {

    private val games = CategoryId("games")
    private val social = CategoryId("social")

    private fun category(
        id: CategoryId,
        name: String,
        limit: Duration,
        packages: Set<PackageName>,
    ) = Category(id, name, WeeklyBudget.flat(limit), packages)

    // ---------- basics ----------

    @Test
    fun `a category limit suspends its members and nothing else`() {
        val result = PolicyEngine.evaluate(
            policy = policy(
                categories = listOf(category(games, "Games", 30.minutes, setOf(GAME))),
            ),
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21", byPackage = mapOf(GAME to 30.minutes)),
            now = at("2026-08-21", "18:00"),
        )

        assertEquals(setOf(GAME), result.suspend.keys)
        assertEquals(BudgetLevel.EXHAUSTED, result.categoryBudgets.getValue(games).level)
    }

    @Test
    fun `an under-budget category blocks nothing`() {
        val result = PolicyEngine.evaluate(
            policy = policy(
                categories = listOf(category(games, "Games", 1.hours, setOf(GAME, YOUTUBE))),
            ),
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21", byPackage = mapOf(GAME to 20.minutes)),
            now = at("2026-08-21", "18:00"),
        )

        assertEquals(emptyMap<Any, Any>(), result.suspend)
        assertEquals(40.minutes, result.categoryBudgets.getValue(games).remaining)
    }

    @Test
    fun `usage from every member counts toward the category`() {
        val result = PolicyEngine.evaluate(
            policy = policy(
                categories = listOf(category(games, "Games", 1.hours, setOf(GAME, YOUTUBE))),
            ),
            installedPackages = ALL_PACKAGES,
            usage = usage(
                "2026-08-21",
                byPackage = mapOf(GAME to 35.minutes, YOUTUBE to 25.minutes),
            ),
            now = at("2026-08-21", "18:00"),
        )

        assertEquals(BudgetLevel.EXHAUSTED, result.categoryBudgets.getValue(games).level)
        assertEquals(setOf(GAME, YOUTUBE), result.suspend.keys)
    }

    @Test
    fun `the reason names the category`() {
        val result = PolicyEngine.evaluate(
            policy = policy(
                categories = listOf(category(games, "Games", 30.minutes, setOf(GAME))),
            ),
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21", byPackage = mapOf(GAME to 30.minutes)),
            now = at("2026-08-21", "18:00"),
        )

        val reason = result.suspend[GAME]
        assertTrue(reason is Reason.CategoryBudgetExhausted)
        assertEquals("Games", (reason as Reason.CategoryBudgetExhausted).categoryName)
        assertEquals(games, reason.categoryId)
        assertEquals(at("2026-08-22", "04:00"), reason.liftsAt)
    }

    // ---------- an app in more than one category ----------

    @Test
    fun `time in a shared app is spent from every category it belongs to`() {
        // GAME is both a game and social. Twenty minutes in it spends twenty
        // minutes of each — that is what the two limits together mean.
        val result = PolicyEngine.evaluate(
            policy = policy(
                categories = listOf(
                    category(games, "Games", 1.hours, setOf(GAME)),
                    category(social, "Social", 1.hours, setOf(GAME, YOUTUBE)),
                ),
            ),
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21", byPackage = mapOf(GAME to 20.minutes)),
            now = at("2026-08-21", "18:00"),
        )

        assertEquals(40.minutes, result.categoryBudgets.getValue(games).remaining)
        assertEquals(40.minutes, result.categoryBudgets.getValue(social).remaining)
    }

    @Test
    fun `exhausting one category blocks a shared app while the other still has room`() {
        val result = PolicyEngine.evaluate(
            policy = policy(
                categories = listOf(
                    category(games, "Games", 30.minutes, setOf(GAME)),
                    category(social, "Social", 3.hours, setOf(GAME, YOUTUBE)),
                ),
            ),
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21", byPackage = mapOf(GAME to 30.minutes)),
            now = at("2026-08-21", "18:00"),
        )

        // The shared app is blocked by the tighter category.
        assertTrue(result.suspend.containsKey(GAME))
        // The other member of the roomier category is untouched.
        assertNull(result.suspend[YOUTUBE])
        assertEquals(BudgetLevel.EXHAUSTED, result.categoryBudgets.getValue(games).level)
        assertEquals(BudgetLevel.NORMAL, result.categoryBudgets.getValue(social).level)
    }

    @Test
    fun `a category with no members is never exhausted by other apps`() {
        val result = PolicyEngine.evaluate(
            policy = policy(
                categories = listOf(category(games, "Games", 5.minutes, emptySet())),
            ),
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21", deviceTotal = 5.hours, byPackage = mapOf(GAME to 5.hours)),
            now = at("2026-08-21", "18:00"),
        )

        assertEquals(emptyMap<Any, Any>(), result.suspend)
        // A five-minute budget sits inside the grace threshold from the outset,
        // which is correct — what matters is that no one else's time spent it.
        assertEquals(false, result.categoryBudgets.getValue(games).isExhausted)
        assertEquals(5.minutes, result.categoryBudgets.getValue(games).remaining)
    }

    // ---------- exemptions ----------

    @Test
    fun `an always-allowed app is not blocked by its category`() {
        val result = PolicyEngine.evaluate(
            policy = policy(
                appRules = mapOf(HOMEWORK to AppRule.AlwaysAllowed),
                categories = listOf(category(games, "Games", 5.minutes, setOf(HOMEWORK, GAME))),
            ),
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21", byPackage = mapOf(GAME to 10.minutes)),
            now = at("2026-08-21", "18:00"),
        )

        assertNull(result.suspend[HOMEWORK])
        assertTrue(result.suspend.containsKey(GAME))
    }

    @Test
    fun `an exempt app does not spend its category's allowance`() {
        val result = PolicyEngine.evaluate(
            policy = policy(
                appRules = mapOf(HOMEWORK to AppRule.AlwaysAllowed),
                categories = listOf(category(games, "Games", 1.hours, setOf(HOMEWORK, GAME))),
            ),
            installedPackages = ALL_PACKAGES,
            usage = usage(
                "2026-08-21",
                byPackage = mapOf(HOMEWORK to 3.hours, GAME to 10.minutes),
            ),
            now = at("2026-08-21", "18:00"),
        )

        // Only GAME's ten minutes count; HOMEWORK's three hours are exempt.
        assertEquals(50.minutes, result.categoryBudgets.getValue(games).remaining)
        assertEquals(emptyMap<Any, Any>(), result.suspend)
    }

    // ---------- interaction with other rules ----------

    @Test
    fun `a parent block still outranks a category limit`() {
        val result = PolicyEngine.evaluate(
            policy = policy(
                appRules = mapOf(GAME to AppRule.Blocked()),
                categories = listOf(category(games, "Games", 30.minutes, setOf(GAME))),
            ),
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21", byPackage = mapOf(GAME to 30.minutes)),
            now = at("2026-08-21", "18:00"),
        )

        assertEquals(Reason.ManuallyBlocked, result.suspend[GAME])
    }

    @Test
    fun `a category grant reopens an exhausted category`() {
        val now = at("2026-08-21", "18:00")
        val result = PolicyEngine.evaluate(
            policy = policy(
                categories = listOf(category(games, "Games", 30.minutes, setOf(GAME))),
            ),
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21", byPackage = mapOf(GAME to 30.minutes)),
            grants = listOf(
                grant(GrantTarget.Category(games), 15.minutes, from = now, until = at("2026-08-22", "04:00")),
            ),
            now = now,
        )

        assertEquals(15.minutes, result.categoryBudgets.getValue(games).remaining)
        assertEquals(emptyMap<Any, Any>(), result.suspend)
    }

    // ---------- nested categories ----------

    @Test
    fun `a parent category is spent by time in a nested one`() {
        val result = PolicyEngine.evaluate(
            policy = policy(
                categories = listOf(
                    Category(
                        id = CategoryId("fun"),
                        name = "Fun",
                        budget = WeeklyBudget.flat(30.minutes),
                        subcategories = setOf(games),
                    ),
                    category(games, "Games", 3.hours, setOf(GAME)),
                ),
            ),
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21", byPackage = mapOf(GAME to 30.minutes)),
            now = at("2026-08-21", "18:00"),
        )

        // The roomy inner category is fine; the tighter outer one has run out.
        assertEquals(BudgetLevel.NORMAL, result.categoryBudgets.getValue(games).level)
        assertEquals(BudgetLevel.EXHAUSTED, result.categoryBudgets.getValue(CategoryId("fun")).level)

        val reason = result.suspend[GAME]
        assertTrue(reason is Reason.CategoryBudgetExhausted)
        assertEquals("Fun", (reason as Reason.CategoryBudgetExhausted).categoryName)
    }

    @Test
    fun `an app reachable twice does not drain a parent twice as fast`() {
        val result = PolicyEngine.evaluate(
            policy = policy(
                categories = listOf(
                    Category(
                        id = CategoryId("fun"),
                        name = "Fun",
                        budget = WeeklyBudget.flat(1.hours),
                        subcategories = setOf(games, social),
                    ),
                    category(games, "Games", 3.hours, setOf(GAME)),
                    category(social, "Social", 3.hours, setOf(GAME, YOUTUBE)),
                ),
            ),
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21", byPackage = mapOf(GAME to 20.minutes)),
            now = at("2026-08-21", "18:00"),
        )

        // Twenty minutes, counted once, not forty.
        assertEquals(40.minutes, result.categoryBudgets.getValue(CategoryId("fun")).remaining)
    }

    @Test
    fun `a loop between categories does not hang enforcement`() {
        val result = PolicyEngine.evaluate(
            policy = policy(
                categories = listOf(
                    Category(
                        id = CategoryId("fun"),
                        name = "Fun",
                        budget = WeeklyBudget.flat(1.hours),
                        subcategories = setOf(games),
                    ),
                    Category(
                        id = games,
                        name = "Games",
                        budget = WeeklyBudget.flat(1.hours),
                        packages = setOf(GAME),
                        subcategories = setOf(CategoryId("fun")),
                    ),
                ),
            ),
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21", byPackage = mapOf(GAME to 10.minutes)),
            now = at("2026-08-21", "18:00"),
        )

        assertEquals(50.minutes, result.categoryBudgets.getValue(games).remaining)
        assertEquals(50.minutes, result.categoryBudgets.getValue(CategoryId("fun")).remaining)
    }

    // ---------- warning scheduling ----------

    @Test
    fun `the next warning follows the tightest budget covering the foreground app`() {
        val now = at("2026-08-21", "10:00")
        val result = PolicyEngine.evaluate(
            policy = policy(
                deviceBudget = WeeklyBudget.flat(5.hours),
                categories = listOf(category(games, "Games", 1.hours, setOf(GAME))),
            ),
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21"),
            deviceState = DeviceState(foregroundPackage = GAME),
            now = now,
        )

        // The category has 60 minutes left, the device 5 hours, so the notice at
        // 10 minutes remaining fires in 50 minutes.
        assertEquals(now.plusSeconds(50 * 60), result.nextChangeAt)
    }
}
