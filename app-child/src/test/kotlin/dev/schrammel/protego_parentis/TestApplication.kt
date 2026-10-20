package dev.schrammel.protego_parentis

import android.app.Application
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.schrammel.protego_parentis.diagnostics.CrashRecovery
import dev.schrammel.protego_parentis.sync.RealtimeConnectivityWatcher
import dev.schrammel.protego_parentis.sync.SyncConfigStore
import dev.schrammel.protego_parentis.sync.SyncGraph
import dev.schrammel.protego_parentis.update.UpdateCheckWorker

/**
 * The application Robolectric tests run under.
 *
 * Robolectric does not run `androidx.startup`'s InitializationProvider, so the
 * WorkManager the production app gets from the merged manifest never arrives in
 * a test process — and every test that reached a `WorkManager.getInstance`
 * call (the poll worker, the update checker) failed with
 * `IllegalStateException: WorkManager is not initialized properly` before its
 * body even ran. Initialising the *test* WorkManager here, before
 * [DementorApplication.onCreate] schedules its first periodic worker, gives
 * every test the same environment the phone has: a real WorkManager, backed by
 * a synchronous executor so enqueued work runs inline.
 *
 * Wired globally through `robolectric.properties`'s `application` key, so no
 * test class has to know about it.
 */
class TestApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Before super.onCreate's boot sequence: DementorApplication schedules the
        // update-check worker on launch, and that call needs a WorkManager to
        // exist. Robolectric never runs the startup provider that would have put
        // one there, so the test WorkManager is installed first.
        WorkManagerTestInitHelper.initializeTestWorkManager(
            this,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        // Robolectric gives every test a fresh application and preference files;
        // process-wide state that isn't keyed on what's stored must follow suit
        // or one test's storage is served to the next. (In production this is
        // never called.) The store container is reset by AppRegistry.install.
        SyncGraph.resetForTest()
        SyncConfigStore.resetForTest()
        // The watcher's installed flag survives test methods; without the
        // reset its second install is a no-op and the fresh app gets no
        // registered callback.
        RealtimeConnectivityWatcher.resetForTest()
        // Mirror DementorApplication.onCreate — kept in step with it deliberately:
        // a test process should boot exactly the way the phone does.
        CrashRecovery.install(this) { AppRegistry.restartLauncher }
        AppRegistry.install(this)
        RealtimeConnectivityWatcher.install(this)
        UpdateCheckWorker.schedule(this)
    }
}