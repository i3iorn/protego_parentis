package dev.schrammel.protego_parentis

import android.content.Context
import dev.schrammel.protego_parentis.enforcement.EnforcementService
import dev.schrammel.protego_parentis.sync.core.RuleMutationSink

/**
 * The app shell's composition root.
 *
 * Owns the handful of seams that would otherwise force a lower-level package
 * (`rules`, `ui`, `sync`, `setup`) to import a higher-level one (`enforcement`)
 * just to poke it. [ruleMutationSink] is the first of these — see its own doc
 * comment on [RuleMutationSink] — wired once, here, to the one real
 * implementation, rather than each of the two dozen call sites importing
 * `EnforcementService` directly.
 *
 * Deliberately not a dependency-injection framework: one object, installed
 * once from [DementorApplication.onCreate], holding plain property references.
 * Nothing here is swapped at runtime except in tests.
 */
object AppRegistry {

    lateinit var ruleMutationSink: RuleMutationSink
        private set

    fun install(context: Context) {
        val appContext = context.applicationContext
        ruleMutationSink = RuleMutationSink { EnforcementService.start(appContext) }
    }
}
