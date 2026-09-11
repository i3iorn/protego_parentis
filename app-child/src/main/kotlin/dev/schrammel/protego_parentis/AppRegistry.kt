package dev.schrammel.protego_parentis

import android.content.Context
import dev.schrammel.protego_parentis.diagnostics.RestartLauncher
import dev.schrammel.protego_parentis.enforcement.EnforcementService
import dev.schrammel.protego_parentis.enforcement.NotificationUx
import dev.schrammel.protego_parentis.enforcement.RealtimeSync
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
        private set

    fun install(context: Context) {
        val appContext = context.applicationContext
        ruleMutationSink = RuleMutationSink { EnforcementService.start(appContext) }
        notificationUx = RealNotificationUx(appContext)
        realtimeSync = RealtimeSyncAdapter(appContext)
        restartLauncher = RealRestartLauncher()
    }
}
