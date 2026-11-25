package dev.schrammel.protego_parentis

import android.content.Context
import dev.schrammel.protego_parentis.AppRegistry.install
import dev.schrammel.protego_parentis.AppRegistry.notificationUx
import dev.schrammel.protego_parentis.AppRegistry.realtimeSync
import dev.schrammel.protego_parentis.AppRegistry.restartLauncher
import dev.schrammel.protego_parentis.AppRegistry.ruleMutationSink
import dev.schrammel.protego_parentis.diagnostics.RestartLauncher
import dev.schrammel.protego_parentis.enforcement.EnforcementService
import dev.schrammel.protego_parentis.enforcement.NotificationUx
import dev.schrammel.protego_parentis.enforcement.RealtimeSync
import dev.schrammel.protego_parentis.rules.ChildStores
import dev.schrammel.protego_parentis.sync.CrashReportPublisher
import dev.schrammel.protego_parentis.sync.RealtimeSyncAdapter
import dev.schrammel.protego_parentis.sync.core.RuleMutationSink
import dev.schrammel.protego_parentis.ui.RealNotificationUx
import dev.schrammel.protego_parentis.ui.RealRestartLauncher

/**
 * The app shell's composition root.
 *
 * Owns the handful of seams that would otherwise force a lower-level package
 * (`rules`, `ui`, `sync`, `setup`, `diagnostics`) to import a higher-level one
 * (`enforcement`, `ui`), just to poke it. [ruleMutationSink], [notificationUx],
 * [realtimeSync] and [restartLauncher] are wired once, here, to their one real
 * implementation each — see their own doc comments for why each exists.
 *
 * Deliberately not a dependency-injection framework: one object, installed
 * once from [DementorApplication.onCreate], holding plain property references.
 * Nothing here is swapped at runtime except in tests.
 */
object AppRegistry {

    lateinit var ruleMutationSink: RuleMutationSink
        private set

    lateinit var notificationUx: NotificationUx
        private set

    lateinit var realtimeSync: RealtimeSync
        private set

    lateinit var restartLauncher: RestartLauncher

    /**
     * Null until [install] runs — the crash handler resolves it lazily, so a
     * crash during installation still recovers; it just cannot publish yet.
     */
    var crashReportPublisher: CrashReportPublisher? = null
        private set

    /**
     * The process-wide store instances — see [ChildStores]. Available from the
     * first access after [install]; the container is lazy, so nothing is built
     * that no screen or service ever asks for.
     */
    @Volatile
    private var childStoresInstance: ChildStores? = null

    val childStores: ChildStores
        get() = childStoresInstance ?: synchronized(this) {
            childStoresInstance
                ?: ChildStores(checkNotNull(appContext)).also { childStoresInstance = it }
        }

    private var appContext: Context? = null

    fun install(context: Context) {
        val appContext = context.applicationContext
        this.appContext = appContext
        // A fresh install means a fresh application object; the store container
        // must follow (Robolectric gives every test a new one, and a container
        // cached across tests would serve one test's storage to the next).
        childStoresInstance = null
        ruleMutationSink = RuleMutationSink { EnforcementService.start(appContext) }
        notificationUx = RealNotificationUx(appContext)
        realtimeSync = RealtimeSyncAdapter(appContext)
        restartLauncher = RealRestartLauncher()
        crashReportPublisher = CrashReportPublisher(appContext)
    }
}
