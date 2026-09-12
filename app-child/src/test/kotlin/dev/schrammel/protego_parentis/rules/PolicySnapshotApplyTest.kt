package dev.schrammel.protego_parentis.rules

import android.content.Context
import dev.schrammel.protego_parentis.model.InstallPolicy
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [LocalPolicyStore.applySnapshot] used to call each field's setter one at a
 * time — each its own commit and its own version bump. This pins the fix
 * (#154): applying one snapshot is one commit and exactly one version bump,
 * whatever it changes.
 */
@RunWith(RobolectricTestRunner::class)
class PolicySnapshotApplyTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun store() = LocalPolicyStore(context)

    private fun snapshot(version: Long = 1L) = PolicySnapshot(
        version = version,
        dailyLimitMinutes = 120,
        categories = emptyList(),
        appLimits = mapOf("com.example.a" to 30),
        exemptPackages = setOf("com.example.school"),
        blockedPackages = setOf("com.example.bad"),
        hiddenPackages = setOf("com.example.hidden"),
        networkBlockedPackages = setOf("com.example.offline"),
        installPolicy = "GATED",
        installWindowUntil = 1_700_000_000_000L,
        webFilterProfile = "OFF",
        webFilterCustomHost = "",
        webFilterSafeSearch = false,
        webFilterYoutube = false,
        webFilterIncognito = false,
        webFilterBlockedUrls = emptySet(),
        webFilterAllowedUrls = emptySet(),
        webFilterSuspendBrowsers = false,
        webFilterCountVisits = false,
        goalEnabled = false,
        goalMaxMinutes = 0,
        goalPrize = "",
        disabledFeatures = emptySet(),
    )

    @Test
    fun `applying a snapshot bumps the version exactly once`() {
        val local = store()
        val before = local.load().version

        local.applySnapshot(snapshot())

        assertEquals(before + 1L, local.load().version)
    }

    @Test
    fun `every field in the snapshot lands`() {
        val local = store()

        local.applySnapshot(snapshot())

        assertEquals(120, local.dailyLimitMinutes)
        assertEquals(mapOf("com.example.a" to 30), local.appLimits)
        assertEquals(setOf("com.example.school"), local.exemptPackages)
        assertEquals(setOf("com.example.bad"), local.blockedPackages)
        assertEquals(setOf("com.example.hidden"), local.hiddenPackages)
        assertEquals(setOf("com.example.offline"), local.networkBlockedPackages)
        assertEquals(InstallPolicy.GATED, local.installPolicy)
    }
}
