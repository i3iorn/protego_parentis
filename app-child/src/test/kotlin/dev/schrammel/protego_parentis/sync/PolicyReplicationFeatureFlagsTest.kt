package dev.schrammel.protego_parentis.sync

import android.content.Context
import dev.schrammel.protego_parentis.rules.PolicySnapshot
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.LocalDeviceId
import dev.schrammel.protego_parentis.security.RoleStore
import dev.schrammel.protego_parentis.sync.core.DeviceId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * #91: `disabledFeatures` edited from a parent's own phone, the way
 * [FeatureSettingsActivity] now does it — a plain replicated field per
 * managed child, rather than a setting each phone previously kept for
 * itself. Two children carry independent sets, the same as every other
 * rule kind.
 */
@RunWith(RobolectricTestRunner::class)
class PolicyReplicationFeatureFlagsTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val childA = DeviceId("CHILD_A")
    private val childB = DeviceId("CHILD_B")

    @Before
    fun setUp() {
        LocalDeviceId.useInTests("PARENT")
        RoleStore(context).choose(DeviceRole.PARENT)
    }

    @After
    fun tearDown() {
        ReplicatedStateStore(context).forgetAll()
        LocalDeviceId.useInTests(null)
    }

    private fun blank(disabledFeatures: Set<String>) = PolicySnapshot(
        version = 1L,
        dailyLimitMinutes = 0,
        categories = emptyList(),
        appLimits = emptyMap(),
        exemptPackages = emptySet(),
        blockedPackages = emptySet(),
        hiddenPackages = emptySet(),
        networkBlockedPackages = emptySet(),
        installPolicy = "NOTIFY",
        installWindowUntil = null,
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
        disabledFeatures = disabledFeatures,
    )

    @Test
    fun `a parent's edit to a child's disabled features round-trips`() {
        PolicyReplication(context).write(blank(setOf("CHAT")), childA)

        assertEquals(setOf("CHAT"), PolicyReplication(context).read(childA)?.disabledFeatures)
    }

    @Test
    fun `two managed children carry independent feature sets`() {
        PolicyReplication(context).write(blank(setOf("CHAT")), childA)
        PolicyReplication(context).write(blank(setOf("WEB_ACTIVITY")), childB)

        assertEquals(setOf("CHAT"), PolicyReplication(context).read(childA)?.disabledFeatures)
        assertEquals(setOf("WEB_ACTIVITY"), PolicyReplication(context).read(childB)?.disabledFeatures)
    }
}
