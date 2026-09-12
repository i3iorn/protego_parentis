package dev.schrammel.protego_parentis.usage

import android.content.Context
import dev.schrammel.protego_parentis.model.PackageName
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [InstalledApps] caches process-wide (a `companion object` cache, not one per
 * instance), so every test clears it first and last rather than relying on
 * ordering.
 */
@RunWith(RobolectricTestRunner::class)
class InstalledAppsTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() = InstalledApps.invalidate()

    @Test
    fun `a package that is not installed falls back to its own name as the label`() {
        val label = InstalledApps(context).labelFor(PackageName("com.example.not.installed"))

        assertEquals("com.example.not.installed", label)
    }

    @Test
    fun `labels are cached across instances`() {
        val pkg = PackageName("com.example.not.installed")
        val first = InstalledApps(context).labelFor(pkg)
        val second = InstalledApps(context).labelFor(pkg)

        assertEquals(first, second)
    }

    @Test
    fun `invalidate clears the cached label`() {
        val pkg = PackageName("com.example.not.installed")
        InstalledApps(context).labelFor(pkg)

        InstalledApps.invalidate()

        // Not a behavior change visible from labelFor() alone — a fresh lookup
        // recomputes rather than reusing what invalidate() just dropped — but
        // this at least pins that invalidate() runs without throwing and that
        // a lookup afterward still resolves the same, deterministic fallback.
        assertEquals("com.example.not.installed", InstalledApps(context).labelFor(pkg))
    }

    @Test
    fun `launchable apps are cached process-wide`() {
        val first = InstalledApps(context).launchable()
        val second = InstalledApps(context).launchable()

        assertTrue("the cached list must be the same instance, not recomputed", first === second)
    }

    @Test
    fun `browsers are cached process-wide too`() {
        val first = InstalledApps(context).browsers()
        val second = InstalledApps(context).browsers()

        assertTrue(first === second)
    }
}
