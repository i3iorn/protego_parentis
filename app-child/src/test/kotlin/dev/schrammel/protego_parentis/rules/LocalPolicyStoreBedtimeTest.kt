package dev.schrammel.protego_parentis.rules

import android.content.Context
import dev.schrammel.protego_parentis.model.Defaults
import dev.schrammel.protego_parentis.model.ScheduleBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * Bedtime as a [StoredSchedule] with [ScheduleBlock.Kind.BEDTIME] at
 * [ScheduleStore.BEDTIME_ID], rather than as four dedicated fields (#12).
 * [LocalPolicyStore.bedtime]'s own shape is unchanged, so this pins that the
 * move underneath it didn't change what a caller sees.
 */
@RunWith(RobolectricTestRunner::class)
class LocalPolicyStoreBedtimeTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun store() = LocalPolicyStore(context)
    private fun scheduleStore() = ScheduleStore(context)

    @Test
    fun `nobody has set a bedtime yet`() {
        val setting = store().bedtime

        assertFalse(setting.enabled)
        assertEquals(Defaults.BEDTIME_START, setting.start)
        assertEquals(Defaults.BEDTIME_END, setting.end)
    }

    @Test
    fun `setting bedtime is remembered by a fresh store instance`() {
        store().bedtime = BedtimeSetting(
            enabled = true,
            start = LocalTime.of(21, 30),
            end = LocalTime.of(7, 0),
            days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
        )

        val reread = LocalPolicyStore(context).bedtime
        assertTrue(reread.enabled)
        assertEquals(LocalTime.of(21, 30), reread.start)
        assertEquals(LocalTime.of(7, 0), reread.end)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), reread.days)
    }

    /** The actual storage this now goes through — proves it, not just the round trip. */
    @Test
    fun `bedtime lives in ScheduleStore under the fixed id, with the bedtime kind`() {
        store().bedtime = BedtimeSetting(enabled = true, start = LocalTime.of(21, 0), end = LocalTime.of(6, 30))

        val schedule = scheduleStore().find(ScheduleStore.BEDTIME_ID)
        assertEquals(ScheduleBlock.Kind.BEDTIME, schedule?.kind)
        assertEquals(true, schedule?.enabled)
    }

    @Test
    fun `an enabled bedtime reaches the engine through load()`() {
        store().bedtime = BedtimeSetting(enabled = true, start = LocalTime.of(21, 0), end = LocalTime.of(6, 30))

        val block = store().load().schedules.singleOrNull { it.kind == ScheduleBlock.Kind.BEDTIME }
        assertEquals(LocalTime.of(21, 0), block?.start)
        assertEquals(LocalTime.of(6, 30), block?.end)
    }

    @Test
    fun `a disabled bedtime does not reach the engine`() {
        store().bedtime = BedtimeSetting(enabled = false, start = LocalTime.of(21, 0), end = LocalTime.of(6, 30))

        assertNull(store().load().schedules.firstOrNull { it.kind == ScheduleBlock.Kind.BEDTIME })
    }

    /** A start equal to its end was always invalid; still is, now via StoredSchedule.isComplete. */
    @Test
    fun `a degenerate bedtime window does not reach the engine`() {
        store().bedtime = BedtimeSetting(enabled = true, start = LocalTime.of(21, 0), end = LocalTime.of(21, 0))

        assertNull(store().load().schedules.firstOrNull { it.kind == ScheduleBlock.Kind.BEDTIME })
    }

    /** #12's own worry: tidying up a homework block must not delete bedtime. */
    @Test
    fun `deleting an unrelated schedule from ScheduleStore leaves bedtime alone`() {
        store().bedtime = BedtimeSetting(enabled = true, start = LocalTime.of(21, 0), end = LocalTime.of(6, 30))
        scheduleStore().upsert(
            StoredSchedule(
                id = "homework",
                name = "Homework",
                days = setOf(1, 2, 3),
                startMinuteOfDay = 16 * 60,
                endMinuteOfDay = 18 * 60,
            ),
        )

        scheduleStore().delete("homework")

        assertEquals(ScheduleBlock.Kind.BEDTIME, scheduleStore().find(ScheduleStore.BEDTIME_ID)?.kind)
    }
}
