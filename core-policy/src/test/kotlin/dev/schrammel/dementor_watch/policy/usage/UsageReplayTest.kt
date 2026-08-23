package dev.schrammel.dementor_watch.policy.usage

import dev.schrammel.dementor_watch.model.PackageName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class UsageReplayTest {

    private val game = "com.supercell.clashroyale"
    private val youtube = "com.google.android.youtube"

    private fun minute(n: Long) = n * 60_000L

    private fun resumed(pkg: String, at: Long) =
        RawUsageEvent(UsageEventKind.APP_RESUMED, pkg, at)

    private fun paused(pkg: String, at: Long) =
        RawUsageEvent(UsageEventKind.APP_PAUSED, pkg, at)

    private fun screenOff(at: Long) =
        RawUsageEvent(UsageEventKind.SCREEN_OFF, "android", at)

    private fun screenOn(at: Long) =
        RawUsageEvent(UsageEventKind.SCREEN_ON, "android", at)


    // ---------- basics ----------

    @Test
    fun `a closed session counts its own length`() {
        val result = UsageReplay.replay(
            listOf(resumed(game, minute(0)), paused(game, minute(20))),
            untilMillis = minute(60),
        )

        assertEquals(20.minutes, result.totals[PackageName(game)])
        assertNull(result.openPackage)
    }

    @Test
    fun `repeated sessions in one app add up`() {
        val result = UsageReplay.replay(
            listOf(
                resumed(game, minute(0)), paused(game, minute(10)),
                resumed(game, minute(30)), paused(game, minute(35)),
            ),
            untilMillis = minute(60),
        )

        assertEquals(15.minutes, result.totals[PackageName(game)])
    }

    @Test
    fun `an app with no events records nothing`() {
        val result = UsageReplay.replay(emptyList(), untilMillis = minute(60))

        assertEquals(emptyMap<PackageName, Duration>(), result.totals)
        assertNull(result.openPackage)
    }

    // ---------- the session that is still running ----------

    @Test
    fun `an open session counts up to the end of the window`() {
        val result = UsageReplay.replay(
            listOf(resumed(game, minute(50))),
            untilMillis = minute(60),
        )

        assertEquals(10.minutes, result.totals[PackageName(game)])
        assertEquals(PackageName(game), result.openPackage)
    }

    @Test
    fun `the open app is the most recently resumed one`() {
        val result = UsageReplay.replay(
            listOf(resumed(game, minute(10)), resumed(youtube, minute(20))),
            untilMillis = minute(30),
        )

        assertEquals(PackageName(youtube), result.openPackage)
    }

    // ---------- screen off ----------

    @Test
    fun `time does not accrue after the screen goes off`() {
        // The phone is put down at minute 10 and picked up hours later. Without
        // this rule the game would collect the whole gap.
        val result = UsageReplay.replay(
            listOf(resumed(game, minute(0)), screenOff(minute(10))),
            untilMillis = minute(600),
        )

        assertEquals(10.minutes, result.totals[PackageName(game)])
        assertNull("nothing is open once the screen is off", result.openPackage)
    }

    @Test
    fun `shutdown also ends open sessions`() {
        val result = UsageReplay.replay(
            listOf(
                resumed(game, minute(0)),
                RawUsageEvent(UsageEventKind.SHUTDOWN, "android", minute(5)),
            ),
            untilMillis = minute(600),
        )

        assertEquals(5.minutes, result.totals[PackageName(game)])
    }

    // ---------- regressions: usage silently stopping ----------

    @Test
    fun `unlocking back into the same app keeps counting`() {
        // The reported bug. Android does not always emit a fresh resume when you
        // unlock straight back into the app you were already using, so the session
        // stayed closed and the app stopped being counted until you switched apps.
        val result = UsageReplay.replay(
            listOf(
                resumed(game, minute(0)),
                screenOff(minute(10)),
                screenOn(minute(40)),
            ),
            untilMillis = minute(50),
        )

        // Ten minutes before the screen went off, ten after it came back.
        assertEquals(20.minutes, result.totals[PackageName(game)])
        assertEquals(PackageName(game), result.openPackage)
    }

    @Test
    fun `time on the lock screen before unlocking is not counted`() {
        // The screen comes on at 40 and the keyguard is dismissed at 45. Only the
        // time after unlocking is real use.
        val result = UsageReplay.replay(
            listOf(
                resumed(game, minute(0)),
                screenOff(minute(10)),
                screenOn(minute(40)),
                screenOn(minute(45)),
            ),
            untilMillis = minute(50),
        )

        assertEquals(15.minutes, result.totals[PackageName(game)])
    }

    @Test
    fun `waking to a different app does not revive the old one`() {
        val result = UsageReplay.replay(
            listOf(
                resumed(game, minute(0)),
                screenOff(minute(10)),
                screenOn(minute(40)),
                resumed(youtube, minute(41)),
            ),
            untilMillis = minute(50),
        )

        assertEquals(11.minutes, result.totals[PackageName(game)])
        assertEquals(9.minutes, result.totals[PackageName(youtube)])
    }

    @Test
    fun `leaving an app before locking means it does not come back on wake`() {
        val result = UsageReplay.replay(
            listOf(
                resumed(game, minute(0)),
                paused(game, minute(10)),
                screenOff(minute(11)),
                screenOn(minute(40)),
            ),
            untilMillis = minute(50),
        )

        assertEquals(10.minutes, result.totals[PackageName(game)])
        assertNull(result.openPackage)
    }

    @Test
    fun `waking with nothing previously open counts nothing`() {
        val result = UsageReplay.replay(
            listOf(screenOn(minute(10))),
            untilMillis = minute(50),
        )

        assertEquals(emptyMap<PackageName, Duration>(), result.totals)
    }

    @Test
    fun `moving between screens of one app keeps counting`() {
        // Navigating within an app emits pause(first activity) immediately
        // followed by resume(second). The session must survive that.
        //
        // The other half of this fix is in the collector: ACTIVITY_STOPPED is no
        // longer mapped to a close at all. Android emits it for the *outgoing*
        // activity after the incoming one resumes, so it used to shut the session
        // down again a moment after it reopened.
        val result = UsageReplay.replay(
            listOf(
                resumed(game, minute(0)),
                paused(game, minute(10)),
                resumed(game, minute(10)),
            ),
            untilMillis = minute(30),
        )

        assertEquals(30.minutes, result.totals[PackageName(game)])
        assertEquals(PackageName(game), result.openPackage)
    }

    @Test
    fun `a full lock and unlock cycle matches wall clock time`() {
        // Belt and braces: the total across a realistic session must not exceed
        // the time the screen was actually on.
        val result = UsageReplay.replay(
            listOf(
                resumed(game, minute(0)),
                resumed(youtube, minute(5)),
                screenOff(minute(15)),
                screenOn(minute(60)),
                resumed(game, minute(61)),
                screenOff(minute(70)),
            ),
            untilMillis = minute(120),
        )

        val total = result.totals.values.fold(Duration.ZERO, Duration::plus)
        assertEquals(25.minutes, total)
    }

    @Test
    fun `a session resumed after screen off counts again`() {
        val result = UsageReplay.replay(
            listOf(
                resumed(game, minute(0)),
                screenOff(minute(10)),
                resumed(game, minute(100)),
                paused(game, minute(105)),
            ),
            untilMillis = minute(200),
        )

        assertEquals(15.minutes, result.totals[PackageName(game)])
    }

    // ---------- app switching ----------

    @Test
    fun `switching apps without a pause does not count both at once`() {
        // Android does not reliably pause the outgoing app. Counting both would
        // make the totals exceed real elapsed time.
        val result = UsageReplay.replay(
            listOf(resumed(game, minute(0)), resumed(youtube, minute(10))),
            untilMillis = minute(30),
        )

        assertEquals(10.minutes, result.totals[PackageName(game)])
        assertEquals(20.minutes, result.totals[PackageName(youtube)])
    }

    @Test
    fun `total time across apps never exceeds the window`() {
        val window = minute(30)
        val result = UsageReplay.replay(
            listOf(
                resumed(game, minute(0)),
                resumed(youtube, minute(5)),
                resumed(game, minute(12)),
                resumed(youtube, minute(20)),
            ),
            untilMillis = window,
        )

        val total = result.totals.values.fold(Duration.ZERO, Duration::plus)
        assertEquals(30.minutes, total)
    }

    @Test
    fun `a duplicate resume keeps the earlier start`() {
        val result = UsageReplay.replay(
            listOf(
                resumed(game, minute(0)),
                resumed(game, minute(5)),
                paused(game, minute(10)),
            ),
            untilMillis = minute(30),
        )

        assertEquals(10.minutes, result.totals[PackageName(game)])
    }

    // ---------- malformed input ----------

    @Test
    fun `a pause with no matching resume is ignored`() {
        val result = UsageReplay.replay(
            listOf(paused(game, minute(10))),
            untilMillis = minute(30),
        )

        assertEquals(emptyMap<PackageName, Duration>(), result.totals)
    }

    @Test
    fun `events arriving out of order are sorted before replay`() {
        val result = UsageReplay.replay(
            listOf(paused(game, minute(20)), resumed(game, minute(0))),
            untilMillis = minute(30),
        )

        assertEquals(20.minutes, result.totals[PackageName(game)])
    }
}
