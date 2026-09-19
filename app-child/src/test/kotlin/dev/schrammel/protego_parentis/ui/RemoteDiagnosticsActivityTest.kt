package dev.schrammel.protego_parentis.ui

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.security.ParentSession
import dev.schrammel.protego_parentis.sync.RemoteDiagnosticsStore
import dev.schrammel.protego_parentis.sync.core.DeviceId
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

/**
 * A parent's view of a remote child's diagnostic log — the request/cache
 * shape mirrors [AppPickerActivityTest]'s, but with no [Background] load in
 * the way: rendering here is synchronous, so no `awaitRow`-style retry is
 * needed, only advancing the looper past [RemoteDiagnosticsActivity]'s own
 * `postDelayed` poll.
 */
@RunWith(RobolectricTestRunner::class)
class RemoteDiagnosticsActivityTest {

    private val context get() = RuntimeEnvironment.getApplication()
    private val childId = DeviceId("CHILD1")

    @Before
    fun setUp() {
        ParentSession.unlock()
    }

    @After
    fun tearDown() {
        ParentSession.lock()
        context.getSharedPreferences("remote_diagnostics", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `offers to request a log when nothing has arrived yet`() {
        val activity = Robolectric.buildActivity(
            RemoteDiagnosticsActivity::class.java,
            RemoteDiagnosticsActivity.intent(context, childId),
        ).setup().get()

        requireNotNull(findText(activity.window.decorView, activity.getString(R.string.remote_diagnostics_empty))) {
            "the empty hint should show before any log has arrived"
        }
        requireNotNull(findButton(activity.window.decorView, activity.getString(R.string.remote_diagnostics_request))) {
            "the request button should always be offered"
        }
    }

    @Test
    fun `shows a cached log immediately, with a share button`() {
        RemoteDiagnosticsStore(context).setLog(childId, "1700000000000|SERVICE_STARTED|")

        val activity = Robolectric.buildActivity(
            RemoteDiagnosticsActivity::class.java,
            RemoteDiagnosticsActivity.intent(context, childId),
        ).setup().get()

        requireNotNull(findText(activity.window.decorView, "SERVICE_STARTED", exact = false)) {
            "the cached log's content should be rendered"
        }
        requireNotNull(findButton(activity.window.decorView, activity.getString(R.string.diagnostics_share))) {
            "a share button should appear once there is something to share"
        }
    }

    @Test
    fun `polls for a fresh log after requesting one, and shows it once it lands`() {
        val activity = Robolectric.buildActivity(
            RemoteDiagnosticsActivity::class.java,
            RemoteDiagnosticsActivity.intent(context, childId),
        ).setup().get()

        val button = requireNotNull(
            findButton(activity.window.decorView, activity.getString(R.string.remote_diagnostics_request)),
        )
        // Sync is not configured in a test, so publishing is a no-op inside
        // SyncPublisher rather than a network call — this pins that the
        // button is wired to something at all, and that clicking it does
        // not crash.
        button.performClick()

        // The real reply would arrive over sync, asynchronously and with
        // nothing to call back into this screen — simulated here by writing
        // straight to the store the way TelemetryInboundHandler.rememberDiagnosticsReport
        // does, so this exercises only this screen's own polling for it.
        RemoteDiagnosticsStore(context).setLog(childId, "1700000000000|SERVICE_STARTED|")

        // Past pollForFreshLog's first delayed check-back.
        shadowOf(Looper.getMainLooper()).idleFor(6, TimeUnit.SECONDS)

        requireNotNull(findText(activity.window.decorView, "SERVICE_STARTED", exact = false)) {
            "the log that arrived after the request should appear without leaving this screen"
        }
    }

    private fun findButton(view: View, text: String): Button? {
        if (view is Button && view.text == text) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findButton(view.getChildAt(i), text)?.let { return it }
            }
        }
        return null
    }

    private fun findText(view: View, text: String, exact: Boolean = true): TextView? {
        if (view is TextView && view !is Button) {
            val match = if (exact) view.text == text else view.text.contains(text)
            if (match) return view
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findText(view.getChildAt(i), text, exact)?.let { return it }
            }
        }
        return null
    }
}
