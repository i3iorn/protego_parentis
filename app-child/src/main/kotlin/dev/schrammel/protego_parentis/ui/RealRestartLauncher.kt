package dev.schrammel.protego_parentis.ui

import android.app.Application
import android.content.Intent
import dev.schrammel.protego_parentis.diagnostics.RestartLauncher

/**
 * [RestartLauncher], implemented here because knowing which screen is the
 * app's own known-good starting point is what `ui` is for.
 *
 * The same screen a normal cold launch opens — it already knows how to route
 * to the right place for whatever this phone is (see
 * [HomeActivity.routeByRole]), which is safer than trying to remember where
 * the crash happened and return there.
 */
class RealRestartLauncher : RestartLauncher {
    override fun restart(app: Application) {
        val intent = Intent(app, HomeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        app.startActivity(intent)
    }
}
