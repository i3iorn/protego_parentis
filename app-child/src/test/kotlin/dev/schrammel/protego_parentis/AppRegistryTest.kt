package dev.schrammel.protego_parentis

import dev.schrammel.protego_parentis.enforcement.EnforcementService
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * [AppRegistry.ruleMutationSink] replaced two dozen direct
 * `EnforcementService.start()` call sites across `rules`, `ui`, `sync` and
 * `setup` — this pins the one thing that matters about the seam: calling it
 * still actually wakes enforcement.
 */
@RunWith(RobolectricTestRunner::class)
class AppRegistryTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `rulesChanged wakes EnforcementService`() {
        AppRegistry.install(context)

        AppRegistry.ruleMutationSink.rulesChanged()

        val started = shadowOf(context).nextStartedService
        assertEquals(EnforcementService::class.java.name, started?.component?.className)
    }
}
