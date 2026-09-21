package dev.schrammel.protego_parentis.enforcement

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Duration
import java.time.Instant

/**
 * [BootReceiver] can't tell a real BOOT_COMPLETED broadcast from a
 * co-installed app spoofing the same action (#251) — what this pins is that
 * a rapid repeat within the cooldown window is refused, a real gap between
 * two boots is not, and the cooldown is persisted rather than reset by a
 * fresh instance (a spoofed broadcast is itself a way to relaunch the
 * process).
 */
@RunWith(RobolectricTestRunner::class)
class BootTriggerCooldownTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `the first call in a window is allowed`() {
        assertTrue(BootTriggerCooldown(context).tryAcquire(Instant.now(), Duration.ofMinutes(1)))
    }

    @Test
    fun `a repeat within the window is refused`() {
        val cooldown = BootTriggerCooldown(context)
        val now = Instant.now()

        assertTrue(cooldown.tryAcquire(now, Duration.ofMinutes(1)))
        assertFalse(cooldown.tryAcquire(now.plusSeconds(1), Duration.ofMinutes(1)))
    }

    @Test
    fun `a repeat after the window has passed is allowed again`() {
        val cooldown = BootTriggerCooldown(context)
        val now = Instant.now()

        assertTrue(cooldown.tryAcquire(now, Duration.ofMinutes(1)))
        assertTrue(cooldown.tryAcquire(now.plus(Duration.ofMinutes(2)), Duration.ofMinutes(1)))
    }

    @Test
    fun `the cooldown survives a fresh instance, not just an in-memory timestamp`() {
        val now = Instant.now()
        assertTrue(BootTriggerCooldown(context).tryAcquire(now, Duration.ofMinutes(1)))

        // A new instance, the same shape a freshly (re)launched process would
        // construct — the whole point of persisting rather than holding this
        // in a companion-object var.
        assertFalse(BootTriggerCooldown(context).tryAcquire(now.plusSeconds(1), Duration.ofMinutes(1)))
    }

    @Test
    fun `a refused call does not push the recorded time forward`() {
        val cooldown = BootTriggerCooldown(context)
        val first = Instant.now()

        cooldown.tryAcquire(first, Duration.ofMinutes(1))
        cooldown.tryAcquire(first.plusSeconds(1), Duration.ofMinutes(1)) // refused, must not move the clock
        cooldown.tryAcquire(first.plusSeconds(2), Duration.ofMinutes(1)) // still refused

        // Had the refused calls moved the recorded time forward, this would
        // still be within a minute of them rather than of `first`.
        assertTrue(cooldown.tryAcquire(first.plus(Duration.ofMinutes(1)), Duration.ofMinutes(1)))
    }
}
