package dev.schrammel.protego_parentis.usage

import android.content.Context
import dev.schrammel.protego_parentis.model.Defaults
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.UsageSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes

/**
 * [UsageHistoryStore]'s JSON round-trip, retention trim, and the write-and-swap
 * that protects it from a process kill mid-write.
 */
@RunWith(RobolectricTestRunner::class)
class UsageHistoryStoreTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun store() = UsageHistoryStore(context)

    private fun snapshot(day: LocalDate, total: Int = 60, pkg: String? = null, pkgMinutes: Int = 0) =
        UsageSnapshot(
            policyDay = day,
            deviceTotal = total.minutes,
            byPackage = pkg?.let { mapOf(PackageName(it) to pkgMinutes.minutes) }.orEmpty(),
        )

    @Test
    fun `nothing recorded yet reads back empty`() {
        assertTrue(store().all().isEmpty())
        assertNull(store().lastRecordedDay())
    }

    @Test
    fun `a recorded day round-trips, including its per-app breakdown`() {
        val store = store()
        val day = LocalDate.of(2026, 1, 1)
        store.record(snapshot(day, total = 90, pkg = "com.example.app", pkgMinutes = 30))

        val all = store.all()
        assertEquals(1, all.size)
        assertEquals(day, all.single().policyDay)
        assertEquals(90.minutes, all.single().deviceTotal)
        assertEquals(30.minutes, all.single().forPackage(PackageName("com.example.app")))
    }

    @Test
    fun `recording the same day again replaces it rather than duplicating it`() {
        val store = store()
        val day = LocalDate.of(2026, 1, 1)
        store.record(snapshot(day, total = 10))
        store.record(snapshot(day, total = 50))

        val all = store.all()
        assertEquals(1, all.size)
        assertEquals(50.minutes, all.single().deviceTotal)
    }

    @Test
    fun `days come back sorted regardless of recording order`() {
        val store = store()
        val later = LocalDate.of(2026, 1, 5)
        val earlier = LocalDate.of(2026, 1, 1)
        store.record(snapshot(later))
        store.record(snapshot(earlier))

        assertEquals(listOf(earlier, later), store.all().map { it.policyDay })
        assertEquals(later, store.lastRecordedDay())
    }

    @Test
    fun `range returns only days within the inclusive bounds`() {
        val store = store()
        val d1 = LocalDate.of(2026, 1, 1)
        val d2 = LocalDate.of(2026, 1, 2)
        val d3 = LocalDate.of(2026, 1, 3)
        listOf(d1, d2, d3).forEach { store.record(snapshot(it)) }

        assertEquals(listOf(d1, d2), store.range(d1, d2).map { it.policyDay })
    }

    @Test
    fun `an app with zero recorded time is not carried`() {
        val store = store()
        val day = LocalDate.of(2026, 1, 1)
        store.record(snapshot(day, pkg = "com.example.idle", pkgMinutes = 0))

        assertEquals(emptyMap<PackageName, Any>(), store.all().single().byPackage)
    }

    @Test
    fun `retention trims to the oldest days once the cap is exceeded`() {
        val store = store()
        val start = LocalDate.of(2020, 1, 1)
        val total = Defaults.HISTORY_RETENTION_DAYS + 5

        (0 until total).forEach { offset -> store.record(snapshot(start.plusDays(offset.toLong()))) }

        val all = store.all()
        assertEquals(Defaults.HISTORY_RETENTION_DAYS, all.size)
        assertEquals(start.plusDays(5), all.first().policyDay)
        assertEquals(start.plusDays((total - 1).toLong()), all.last().policyDay)
    }

    @Test
    fun `clear removes everything recorded`() {
        val store = store()
        store.record(snapshot(LocalDate.of(2026, 1, 1)))

        store.clear()

        assertTrue(store.all().isEmpty())
    }

    // ---------- sealed at rest (#276) ----------

    /** Stands in for the keystore, which Robolectric doesn't have: reversible, and obviously not the JSON. */
    private fun sealingStore() = UsageHistoryStore(
        context,
        seal = { plain -> plain.reversed() },
        open = { sealed -> sealed.reversed() },
    )

    private val file get() = File(context.filesDir, "usage-history.json")

    @Test
    fun `history is written sealed, and reads back`() {
        sealingStore().record(snapshot(LocalDate.parse("2026-08-21"), pkg = "com.example.chat", pkgMinutes = 30))

        assertFalse(file.readText().contains("com.example.chat"))
        assertEquals(30.minutes, sealingStore().all().single().byPackage[PackageName("com.example.chat")])
    }

    @Test
    fun `an unsealed file from an older build is still read, and sealed on the next write`() {
        UsageHistoryStore(context, seal = { null }, open = { null })
            .record(snapshot(LocalDate.parse("2026-08-21"), pkg = "com.example.chat", pkgMinutes = 30))
        assertTrue(file.readText().contains("com.example.chat"))

        sealingStore().record(snapshot(LocalDate.parse("2026-08-22")))

        assertEquals(2, sealingStore().all().size)
        assertFalse(file.readText().contains("com.example.chat"))
    }
}
