package dev.schrammel.protego_parentis.rules

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.provider.AlarmClock
import android.telecom.TelecomManager
import dev.schrammel.protego_parentis.model.PackageName
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * The apps a bedtime or a spent budget must never touch, asked of the device
 * rather than assumed: the clock differs by manufacturer, and a clock missing
 * from a fixed list is one that bedtime silences.
 */
@RunWith(RobolectricTestRunner::class)
class SafetyAllowlistTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()

    private fun handles(action: String, pkg: String, system: Boolean = true) {
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = pkg
                name = "$pkg.AlarmActivity"
                applicationInfo = ApplicationInfo().apply {
                    packageName = pkg
                    flags = if (system) ApplicationInfo.FLAG_SYSTEM else 0
                }
            }
        }
        shadowOf(app.packageManager).addResolveInfoForIntent(Intent(action), info)
    }

    @Test
    fun `this app is always allowed`() {
        assertTrue(PackageName(app.packageName) in SafetyAllowlist.of(app))
    }

    @Test
    fun `a clock from any manufacturer is allowed if it answers show alarms`() {
        handles(AlarmClock.ACTION_SHOW_ALARMS, "com.coloros.alarmclock")

        assertTrue(PackageName("com.coloros.alarmclock") in SafetyAllowlist.of(app))
    }

    @Test
    fun `a clock that only answers set alarm is allowed too`() {
        handles(AlarmClock.ACTION_SET_ALARM, "com.example.clock")

        assertTrue(PackageName("com.example.clock") in SafetyAllowlist.of(app))
    }

    /**
     * Everything here is exempt from every rule, and any app can declare it
     * shows alarms. An installed alarm app with a browser inside would
     * otherwise be a way round bedtime.
     */
    @Test
    fun `an installed app that merely claims to show alarms is not allowed`() {
        handles(AlarmClock.ACTION_SHOW_ALARMS, "com.example.alarmwithbrowser", system = false)

        assertFalse(PackageName("com.example.alarmwithbrowser") in SafetyAllowlist.of(app))
    }

    // ---------- default dialer (#293) ----------

    private fun installAsDefaultDialer(pkg: String, system: Boolean) {
        shadowOf(app.packageManager).installPackage(
            PackageInfo().apply {
                packageName = pkg
                applicationInfo = ApplicationInfo().apply {
                    packageName = pkg
                    flags = if (system) ApplicationInfo.FLAG_SYSTEM else 0
                }
            },
        )
        shadowOf(app.getSystemService(TelecomManager::class.java)).setDefaultDialerPackage(pkg)
    }

    @Test
    fun `the phone's own dialer is allowed`() {
        installAsDefaultDialer("com.android.dialer", system = true)

        assertTrue(PackageName("com.android.dialer") in SafetyAllowlist.of(app))
    }

    /**
     * Whoever holds the phone chooses the default dialer, and everything here
     * is exempt from every rule: an installed dialer with chat built in must
     * not escape bedtime by being made the default.
     */
    @Test
    fun `an installed app made the default dialer is not allowed`() {
        installAsDefaultDialer("com.example.chattydialer", system = false)

        assertFalse(PackageName("com.example.chattydialer") in SafetyAllowlist.of(app))
    }
}
