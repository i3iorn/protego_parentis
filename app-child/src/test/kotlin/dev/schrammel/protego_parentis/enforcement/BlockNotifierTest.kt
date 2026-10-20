package dev.schrammel.protego_parentis.enforcement

import android.app.NotificationManager
import android.content.Context
import dev.schrammel.protego_parentis.AppRegistry
import dev.schrammel.protego_parentis.model.BudgetLevel
import dev.schrammel.protego_parentis.model.BudgetStatus
import dev.schrammel.protego_parentis.model.ChildId
import dev.schrammel.protego_parentis.model.Enforcement
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.Policy
import dev.schrammel.protego_parentis.model.Reason
import dev.schrammel.protego_parentis.model.UsageSnapshot
import dev.schrammel.protego_parentis.ui.HomeActivity
import dev.schrammel.protego_parentis.usage.CollectedUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Duration.Companion.hours

/**
 * Pins the companion notification for a restriction beginning (HOME-UX
 * phase 3): it fires on the transition into the restriction, not on every
 * tick; it stays quiet while an emergency is already doing the telling; the
 * announcement can be silenced without the restriction going anywhere; and
 * the tap carries the child to the why-dialog for the app they were in.
 */
@RunWith(RobolectricTestRunner::class)
class BlockNotifierTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val zone: ZoneId = ZoneId.systemDefault()
    private val game = PackageName("com.example.game")

    private lateinit var notifier: BlockNotifier

    @Before
    fun setUp() {
        AppRegistry.install(context)
        notifier = BlockNotifier(context)
    }

    private val notifications get() = shadowOf(
        context.getSystemService(NotificationManager::class.java),
    ).allNotifications

    @Test
    fun `notifies on the transition into a restriction and not on every tick after`() {
        // The whole point of the signature: the first exhausted evaluation
        // knocks; the identical one a tick later does not. A countdown that
        // re-notifies every fifteen seconds trains the reader to swipe without
        // reading.
        notifier.onEvaluated(snapshot(budgetExhausted = true))
        assertEquals(1, notifications.size)

        notifier.onEvaluated(snapshot(budgetExhausted = true))
        assertEquals("an unchanged restriction must not re-notify", 1, notifications.size)
    }

    @Test
    fun `a re-entry after the restriction lifts announces again`() {
        // Remembering the null is what makes this work: without it, leaving
        // quietly and re-entering would look identical to never having left.
        notifier.onEvaluated(snapshot(budgetExhausted = true))
        // The child swiped the knock away: the shade is empty. Whether the
        // re-entry announces is decided by the remembered signature, not by
        // the shade — and the shadow keeps one entry per id, so a re-post
        // replaces rather than adds.
        context.getSystemService(NotificationManager::class.java)
            .cancel(NotificationIds.BLOCK_STARTED)
        assertEquals(0, notifications.size)

        notifier.onEvaluated(snapshot())
        notifier.onEvaluated(snapshot(budgetExhausted = true))
        assertEquals("re-entry is a new event", 1, notifications.size)
    }

    @Test
    fun `an emergency does not double-announce`() {
        // The emergency has its own telling; a second notification for the
        // same event is noise at the worst possible moment.
        notifier.onEvaluated(snapshot(budgetExhausted = true, emergency = true))
        assertEquals(0, notifications.size)
    }

    @Test
    fun `the silence switch turns off the announcement, not the restriction`() {
        // Silenced by choice: the door stays locked, only the knock stops.
        NotificationPreferences(context).setEnabled(NotificationKind.BLOCK_STARTED, false)
        notifier.onEvaluated(snapshot(budgetExhausted = true))
        assertEquals(0, notifications.size)

        // And it does not catch up retroactively — re-enabling the switch
        // announces the *next* restriction, not one already standing.
        NotificationPreferences(context).setEnabled(NotificationKind.BLOCK_STARTED, true)
        notifier.onEvaluated(snapshot(budgetExhausted = true))
        assertEquals("an already-announced state stays announced", 0, notifications.size)
    }

    @Test
    fun `the tap deep-links to the why-dialog for the app the child was in`() {
        // HOME-UX phase 3: the child was mid-app when the block hit; the
        // notification lands on that app's explanation, not a generic screen.
        val now = Instant.now()
        notifier.onEvaluated(
            snapshot(
                budgetExhausted = true,
                suspend = mapOf(
                    game to Reason.DeviceBudgetExhausted(
                        now.plus(Duration.ofHours(14)),
                    ),
                ),
                foreground = game,
            ),
        )
        assertEquals(1, notifications.size)

        val intent = notifications.single().contentIntent
        val launched = shadowOf(intent).savedIntent
        assertEquals(
            "the deep link must name the app on screen",
            game.value,
            launched.getStringExtra(HomeActivity.EXTRA_BLOCKED_PACKAGE),
        )
    }

    @Test
    fun `a rule about another app does not claim the app on screen`() {
        // A rule about games has nothing to say about the homework app the
        // child was reading; that launch opens the status card instead.
        val now = Instant.now()
        notifier.onEvaluated(
            snapshot(
                suspend = mapOf(
                    game to Reason.ContextRuleActive(
                        ruleId = "r1",
                        ruleName = "School hours",
                        liftsAt = now.plus(Duration.ofHours(2)),
                    ),
                ),
                foreground = PackageName("com.example.reader"),
            ),
        )
        assertEquals(1, notifications.size)

        val launched = shadowOf(notifications.single().contentIntent).savedIntent
        assertTrue(
            "no deep-link extra must be set for an uninvolved app",
            !launched.hasExtra(HomeActivity.EXTRA_BLOCKED_PACKAGE),
        )
    }

    // ---------- fixtures ----------

    private fun snapshot(
        budgetExhausted: Boolean = false,
        emergency: Boolean = false,
        suspend: Map<PackageName, Reason> = emptyMap(),
        foreground: PackageName? = null,
    ): EnforcementSnapshot {
        val now = Instant.now()
        return EnforcementSnapshot(
            policy = Policy(version = 1, childId = ChildId("c1"), timeZone = zone),
            usage = CollectedUsage(UsageSnapshot.empty(LocalDate.now(zone)), foreground),
            enforcement = Enforcement(
                policyVersion = 1,
                suspend = suspend,
                hide = emptySet(),
                deferred = emptyMap(),
                deviceBudget = if (budgetExhausted) {
                    BudgetStatus(
                        allowed = kotlin.time.Duration.ZERO,
                        used = 2.hours,
                        bonus = kotlin.time.Duration.ZERO,
                        remaining = kotlin.time.Duration.ZERO,
                        level = BudgetLevel.EXHAUSTED,
                    )
                } else {
                    BudgetStatus(
                        allowed = 2.hours,
                        used = kotlin.time.Duration.ZERO,
                        bonus = kotlin.time.Duration.ZERO,
                        remaining = 2.hours,
                        level = BudgetLevel.NORMAL,
                    )
                },
                appBudgets = emptyMap(),
                categoryBudgets = emptyMap(),
                nextChangeAt = now.plus(Duration.ofHours(14)),
                emergency = emergency,
            ),
            reconcile = null,
            evaluatedAt = now,
        )
    }
}
