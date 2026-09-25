package dev.schrammel.protego_parentis.sync

import android.content.Context
import dev.schrammel.protego_parentis.sync.SyncGraph.get

/**
 * The one [SyncCoordinator] the app shares.
 *
 * SyncCoordinator used to be constructed at roughly thirty call sites — one per
 * screen, one per store write in the rule-store factories, one inside
 * [InboxStore.settle] — and each construction rebuilt the whole graph
 * (publisher, receiver, config store, roster, transmitter, signer) only to use
 * a method or two before being discarded. The graph is process-safe by design
 * (SharedPreferences plus the process-wide locks in ReplicatedStateStore and
 * SyncKeying's shared executor), so one shared instance is the composition the
 * per-call construction was accidentally imitating.
 *
 * Tests construct [SyncCoordinator] directly with their own stores; production
 * callers go through [get]. Callers that genuinely need a fresh graph (a
 * settings change that should drop every cache) can still construct one.
 */
object SyncGraph {

    @Volatile
    private var coordinator: SyncCoordinator? = null

    fun get(context: Context): SyncCoordinator =
        coordinator ?: synchronized(this) {
            coordinator ?: SyncCoordinator(context).also { coordinator = it }
        }

    /**
     * Drops the shared instance. Test-only: Robolectric builds a fresh
     * application (and preference files) per test method, so a coordinator
     * cached across tests would silently serve one test's storage to the
     * next. Called from the test application's onCreate.
     */
    fun resetForTest() = synchronized(this) { coordinator = null }
}
