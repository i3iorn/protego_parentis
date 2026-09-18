package dev.schrammel.protego_parentis.sync

import android.content.Context
import dev.schrammel.protego_parentis.security.LocalDeviceId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The parent's "where the time went" list ([DailySummaryStore.latest]'s
 * [DailySummary.topApps]) is built from [TelemetryReplication.topApps], which
 * reads every `telemetry/apps/<label>/m` register still standing.
 *
 * [TelemetryReplication.report]'s job is to clear a register the moment its
 * app is no longer in the fresh top list it was just given — including the
 * empty list a new policy day starts with, which is what makes the list
 * reset at the 04:00 boundary instead of keeping yesterday's entries around
 * forever. It used to try to find "the apps still on record" through
 * [ReplicatedStateStore.children], which only looks one path segment past
 * the prefix it is given; every app register lives two past `telemetry/apps`
 * (the label, then `/m`), so that lookup always came back empty and no
 * register was ever cleared.
 */
@RunWith(RobolectricTestRunner::class)
class TelemetryReplicationTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun telemetry() = TelemetryReplication(context)

    @Before
    fun useAKnownIdentity() {
        LocalDeviceId.useInTests("THISPHONE")
        ReplicatedStateStore(context).forgetAll()
    }

    @After
    fun restore() {
        ReplicatedStateStore(context).forgetAll()
        LocalDeviceId.useInTests(null)
    }

    @Test
    fun `an app that drops out of the top list is cleared, not left behind`() {
        telemetry().report(topApps = listOf(AppTime("Games", 45), AppTime("Chat", 10)))
        assertEquals(setOf("Games", "Chat"), telemetry().topApps().map { it.label }.toSet())

        // A day where "Chat" was not used at all: only "Games" is still in the
        // fresh top list.
        telemetry().report(topApps = listOf(AppTime("Games", 5)))

        assertEquals(setOf("Games"), telemetry().topApps().map { it.label }.toSet())
    }

    @Test
    fun `an empty top list clears every app, the way a new policy day starts`() {
        telemetry().report(topApps = listOf(AppTime("Games", 45), AppTime("Chat", 10)))
        assertEquals(2, telemetry().topApps().size)

        telemetry().report(topApps = emptyList())

        assertEquals(emptyList<AppTime>(), telemetry().topApps())
    }
}
