package dev.schrammel.dementor_watch.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PolicySnapshotTest {

    private fun snapshot() = PolicySnapshot(
        version = 7L,
        dailyLimitMinutes = 120,
        bedtimeEnabled = true,
        bedtimeStartMinute = 21 * 60,
        bedtimeEndMinute = 7 * 60,
        bedtimeDays = setOf(1, 2, 3, 4, 5),
        categories = listOf(
            StoredCategory(
                id = "games",
                name = "Games",
                limitMinutes = 60,
                packages = setOf("com.example.a"),
            ),
        ),
        appLimits = mapOf("com.example.a" to 30),
        exemptPackages = setOf("com.example.school"),
        blockedPackages = setOf("com.example.bad"),
        hiddenPackages = setOf("com.example.hidden"),
        networkBlockedPackages = setOf("com.example.offline"),
        installPolicy = "GATED",
        installWindowUntil = 1_700_000_000_000L,
    )

    @Test
    fun `round-trips through json`() {
        val original = snapshot()

        assertEquals(original, PolicySnapshot.fromJson(original.toJson()))
    }

    @Test
    fun `round-trips through the encoded wire string`() {
        val original = snapshot()

        assertEquals(original, PolicySnapshot.decode(original.encode()))
    }

    @Test
    fun `an absent install window decodes to null`() {
        val original = snapshot().copy(installWindowUntil = null)

        assertEquals(original, PolicySnapshot.decode(original.encode()))
    }

    @Test
    fun `blank or unparseable input decodes to null`() {
        assertNull(PolicySnapshot.decode(null))
        assertNull(PolicySnapshot.decode(""))
        assertNull(PolicySnapshot.decode("not json"))
    }

    @Test
    fun `zero-minute app limits are not carried`() {
        val original = snapshot().copy(appLimits = mapOf("com.example.a" to 0, "com.example.b" to 10))

        val decoded = PolicySnapshot.decode(original.encode())

        assertEquals(mapOf("com.example.b" to 10), decoded?.appLimits)
    }
}
