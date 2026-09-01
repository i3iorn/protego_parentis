package dev.schrammel.protego_parentis.policy

import dev.schrammel.protego_parentis.model.BudgetLevel
import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.model.DeviceContext
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.Reason
import dev.schrammel.protego_parentis.model.RuleEffect
import dev.schrammel.protego_parentis.model.RuleTarget
import dev.schrammel.protego_parentis.model.WeeklyBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** The contextual rules as the enforcement engine actually applies them. */
class ContextEnforcementTest {

    private val schoolWifi = "School-WiFi"

    private fun rule(
        name: String,
        condition: Condition,
        effect: RuleEffect = RuleEffect.Block,
        target: RuleTarget = RuleTarget.Apps(setOf(GAME)),
    ) = ContextRule(name, name, enabled = true, condition, effect, target)

    @Test
    fun `an app is blocked while the rule's condition holds`() {
        val policy = policy().copy(
            contextRules = listOf(rule("No games at school", Condition.OnWifi(schoolWifi))),
        )

        val atSchool = PolicyEngine.evaluate(
            policy = policy,
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21"),
            context = DeviceContext(wifiSsid = schoolWifi),
            now = at("2026-08-21", "10:00"),
        )

        val reason = atSchool.suspend[GAME]
        assertTrue(reason is Reason.ContextRuleActive)
        assertEquals("No games at school", (reason as Reason.ContextRuleActive).ruleName)
        // Context is not a schedule, so there is no honest answer to "until when".
        assertNull(reason.liftsAt)
    }

    @Test
    fun `the same app is free once the condition stops holding`() {
        val policy = policy().copy(
            contextRules = listOf(rule("No games at school", Condition.OnWifi(schoolWifi))),
        )

        val atHome = PolicyEngine.evaluate(
            policy = policy,
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21"),
            context = DeviceContext(wifiSsid = "Home"),
            now = at("2026-08-21", "10:00"),
        )

        assertEquals(emptyMap<Any, Any>(), atHome.suspend)
    }

    @Test
    fun `with no context known, a context rule does not apply`() {
        // Permissions may be missing or the phone offline. A rule that cannot be
        // evaluated must not block, or revoking a permission would tighten the
        // phone rather than loosen it — surprising in the wrong direction.
        val policy = policy().copy(
            contextRules = listOf(rule("No games at school", Condition.OnWifi(schoolWifi))),
        )

        val unknown = PolicyEngine.evaluate(
            policy = policy,
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21"),
            context = DeviceContext.UNKNOWN,
            now = at("2026-08-21", "10:00"),
        )

        assertEquals(emptyMap<Any, Any>(), unknown.suspend)
    }

    @Test
    fun `a contextual allow survives an exhausted budget`() {
        // "Homework apps are unlimited at home" has to beat the daily limit, or it
        // is not really an allowance.
        val policy = policy(deviceBudget = WeeklyBudget.flat(1.hours)).copy(
            contextRules = listOf(
                rule(
                    name = "Homework at home",
                    condition = Condition.OnWifi("Home"),
                    effect = RuleEffect.Allow,
                    target = RuleTarget.Apps(setOf(HOMEWORK)),
                ),
            ),
        )

        val result = PolicyEngine.evaluate(
            policy = policy,
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21", deviceTotal = 3.hours),
            context = DeviceContext(wifiSsid = "Home"),
            now = at("2026-08-21", "18:00"),
        )

        assertNull(result.suspend[HOMEWORK])
        assertTrue(result.suspend.containsKey(GAME))
    }

    @Test
    fun `a contextual rule can replace the daily limit`() {
        val policy = policy(deviceBudget = WeeklyBudget.flat(4.hours)).copy(
            contextRules = listOf(
                rule(
                    name = "Tight away from home",
                    condition = Condition.Not(Condition.OnWifi("Home")),
                    effect = RuleEffect.SetDeviceBudget(WeeklyBudget.flat(30.minutes)),
                    target = RuleTarget.Everything,
                ),
            ),
        )

        val away = PolicyEngine.evaluate(
            policy = policy,
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21", deviceTotal = 45.minutes),
            context = DeviceContext(wifiSsid = schoolWifi),
            now = at("2026-08-21", "18:00"),
        )
        val home = PolicyEngine.evaluate(
            policy = policy,
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21", deviceTotal = 45.minutes),
            context = DeviceContext(wifiSsid = "Home"),
            now = at("2026-08-21", "18:00"),
        )

        assertEquals(BudgetLevel.EXHAUSTED, away.deviceBudget.level)
        assertEquals(30.minutes, away.deviceBudget.allowed)
        // The ordinary limit is untouched at home.
        assertEquals(4.hours, home.deviceBudget.allowed)
    }

    @Test
    fun `the safety allowlist still beats a contextual block`() {
        val policy = policy().copy(
            contextRules = listOf(
                rule("Block everything", Condition.Always, RuleEffect.Block, RuleTarget.Everything),
            ),
        )

        val result = PolicyEngine.evaluate(
            policy = policy,
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21"),
            context = DeviceContext.UNKNOWN,
            now = at("2026-08-21", "18:00"),
        )

        assertNull("the dialer must survive any rule", result.suspend[DIALER])
    }

    @Test
    fun `active rules are reported so the dashboard can show them`() {
        val policy = policy().copy(
            contextRules = listOf(rule("At school", Condition.OnWifi(schoolWifi))),
        )

        val result = PolicyEngine.evaluate(
            policy = policy,
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21"),
            context = DeviceContext(wifiSsid = schoolWifi),
            now = at("2026-08-21", "10:00"),
        )

        assertEquals(listOf("At school"), result.activeContextRules.map { it.name })
    }

    @Test
    fun `a proximity rule can loosen things while a parent is nearby`() {
        val dad = dev.schrammel.protego_parentis.model.DeviceTag("dads-phone")
        val policy = policy(deviceBudget = WeeklyBudget.flat(30.minutes)).copy(
            contextRules = listOf(
                rule(
                    name = "With a grown-up",
                    condition = Condition.NearDevice(dad),
                    effect = RuleEffect.Allow,
                    target = RuleTarget.Everything,
                ),
            ),
        )

        val together = PolicyEngine.evaluate(
            policy = policy,
            installedPackages = ALL_PACKAGES,
            usage = usage("2026-08-21", deviceTotal = 3.hours),
            context = DeviceContext(nearbyDevices = setOf(dad)),
            now = at("2026-08-21", "18:00"),
        )

        assertEquals(emptyMap<PackageName, Reason>(), together.suspend)
    }
}
