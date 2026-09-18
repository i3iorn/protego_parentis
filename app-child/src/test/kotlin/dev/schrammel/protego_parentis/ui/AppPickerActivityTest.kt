package dev.schrammel.protego_parentis.ui

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.ParentSession
import dev.schrammel.protego_parentis.security.RoleStore
import dev.schrammel.protego_parentis.sync.AppInventoryEntry
import dev.schrammel.protego_parentis.sync.AppInventoryStore
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.sync.RosterStore
import dev.schrammel.protego_parentis.sync.core.SyncRole
import dev.schrammel.protego_parentis.sync.core.Vouch
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * #85: [dev.schrammel.protego_parentis.sync.SyncCoordinator.publishAppInventoryRequest]
 * and its answer path always existed — nothing anywhere ever called it. A
 * parent picking apps for a remote child's rules just saw an empty list,
 * forever, with no way to explain why or ask again.
 *
 * A second, later gap in the same feature: tapping the request button sent
 * the message, but nothing on this screen ever checked back for the reply —
 * [AppPickerActivity.onGatedContentVisible]'s one-shot `loaded` guard meant
 * even backgrounding and returning would not pick it up, so the list only
 * ever appeared if a parent happened to fully back out and reopen this exact
 * picker afterwards. From the outside this looked identical to the list
 * being permanently empty. `pollForFreshInventory` is the fix; the test for
 * it below drives the real polling path via the shadow looper rather than
 * asserting the private method exists.
 *
 * This screen fills its rows from [Background], on a real worker thread that
 * posts back to the main looper, so nothing is on screen when `setup()`
 * returns. [awaitRow] is what waits for that — no other test here has needed
 * to, because no other screen under test loads asynchronously.
 */
@RunWith(RobolectricTestRunner::class)
class AppPickerActivityTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private fun enrolChild(id: String) =
        RosterStore(context).enrol(Vouch(id = DeviceId(id), name = id, role = SyncRole.CHILD, publicKey = "key"))

    @After
    fun tearDown() {
        RoleStore(context).choose(DeviceRole.CHILD)
        RosterStore(context).forgetAll()
        ParentSession.lock()
        context.getSharedPreferences("app_inventory", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `offers to request the list when a remote child has none cached yet`() {
        RoleStore(context).choose(DeviceRole.PARENT)
        enrolChild("CHILD1")
        // This screen has no read-only mode: without an unlocked session it
        // sends the visitor to PinActivity and renders nothing at all.
        ParentSession.unlock()
        val activity = Robolectric.buildActivity(
            AppPickerActivity::class.java,
            AppPickerActivity.intent(context, "Pick apps", emptyList(), DeviceId("CHILD1")),
        ).setup().get()

        val button = awaitRow(activity) {
            findButton(it, activity.getString(R.string.app_picker_request_list))
        }
        requireNotNull(button) { "the request-list button should be offered for an empty remote catalog" }

        // Sync is not configured in a test, so publishing is a no-op inside
        // SyncPublisher rather than a network call — what this pins is that the
        // button is wired to something at all, which is the whole of #85.
        button.performClick()
    }

    @Test
    fun `polls for a fresh list after the parent asks for one, and shows it once it lands`() {
        RoleStore(context).choose(DeviceRole.PARENT)
        enrolChild("CHILD1")
        ParentSession.unlock()
        val activity = Robolectric.buildActivity(
            AppPickerActivity::class.java,
            AppPickerActivity.intent(context, "Pick apps", emptyList(), DeviceId("CHILD1")),
        ).setup().get()

        val button = awaitRow(activity) {
            findButton(it, activity.getString(R.string.app_picker_request_list))
        }
        requireNotNull(button) { "the request-list button should be offered for an empty remote catalog" }
        button.performClick()

        // The real reply would arrive over sync, asynchronously and with
        // nothing to call back into this screen — simulated here by writing
        // straight to the store the same way SyncReceiver.rememberAppInventory
        // does, so this exercises only AppPickerActivity's own polling for it,
        // not the transport underneath.
        AppInventoryStore(context).setApps(
            DeviceId("CHILD1"),
            listOf(AppInventoryEntry("com.example.games", "Games")),
        )

        // Past pollForFreshInventory's first delayed check-back, without
        // this screen ever having been backgrounded or reopened. Robolectric's
        // paused looper runs on a virtual clock that real Thread.sleep does not
        // advance, so idle() alone (as awaitRow uses) would never reach a
        // postDelayed callback still five seconds out — the clock itself has
        // to be moved forward.
        shadowOf(Looper.getMainLooper()).idleFor(6, java.util.concurrent.TimeUnit.SECONDS)

        requireNotNull(awaitRow(activity) { findText(it, "Games") }) {
            "the list that arrived after the request should appear without leaving this screen"
        }
    }

    @Test
    fun `does not offer to request a list once that child's apps are cached`() {
        RoleStore(context).choose(DeviceRole.PARENT)
        enrolChild("CHILD1")
        ParentSession.unlock()
        AppInventoryStore(context).setApps(
            DeviceId("CHILD1"),
            listOf(AppInventoryEntry("com.example.games", "Games")),
        )
        val activity = Robolectric.buildActivity(
            AppPickerActivity::class.java,
            AppPickerActivity.intent(context, "Pick apps", emptyList(), DeviceId("CHILD1")),
        ).setup().get()

        // Waits on the app row rather than on the absence of a button, so this
        // cannot pass merely by asserting before the list had loaded.
        requireNotNull(awaitRow(activity) { findText(it, "Games") }) {
            "the cached app should have been listed"
        }
        assertNull(findButton(activity.window.decorView, activity.getString(R.string.app_picker_request_list)))
    }

    @Test
    fun `search field waits for a pause in typing before rebuilding the rows`() {
        RoleStore(context).choose(DeviceRole.PARENT)
        enrolChild("CHILD1")
        ParentSession.unlock()
        AppInventoryStore(context).setApps(
            DeviceId("CHILD1"),
            listOf(
                AppInventoryEntry("com.example.games", "Games"),
                AppInventoryEntry("com.example.photos", "Photos"),
            ),
        )
        val activity = Robolectric.buildActivity(
            AppPickerActivity::class.java,
            AppPickerActivity.intent(context, "Pick apps", emptyList(), DeviceId("CHILD1")),
        ).setup().get()

        requireNotNull(awaitRow(activity) { findText(it, "Photos") }) {
            "both cached apps should be listed before any search is typed"
        }

        val field = requireNotNull(findEditText(activity.window.decorView)) {
            "the search field should be on screen"
        }
        field.setText("pho")

        // Right after typing, still both rows: searchField debounces, so this
        // must not have rebuilt yet.
        requireNotNull(findText(activity.window.decorView, "Games")) {
            "typing should not rebuild the rows immediately"
        }

        // Past searchField's debounce window.
        shadowOf(Looper.getMainLooper()).idleFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)

        requireNotNull(awaitRow(activity) { findText(it, "Photos") }) {
            "the matching app should still be shown once the debounce fires"
        }
        assertNull(findText(activity.window.decorView, "Games"))
    }

    /**
     * Drains the main looper until [find] produces something, or gives up.
     *
     * The work itself runs on [Background]'s own thread, which Robolectric does
     * not control, so idling alone is not enough — this alternates idling with
     * a short wait until the posted result has landed.
     */
    private fun <T> awaitRow(activity: Activity, find: (View) -> T?): T? {
        val main = shadowOf(Looper.getMainLooper())
        repeat(ATTEMPTS) {
            main.idle()
            find(activity.window.decorView)?.let { return it }
            Thread.sleep(POLL_MILLIS)
        }
        main.idle()
        return find(activity.window.decorView)
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

    private fun findEditText(view: View): EditText? {
        if (view is EditText) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findEditText(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun findText(view: View, text: String): TextView? {
        if (view is TextView && view !is Button && view.text == text) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findText(view.getChildAt(i), text)?.let { return it }
            }
        }
        return null
    }

    private companion object {
        const val ATTEMPTS = 100
        const val POLL_MILLIS = 10L
    }
}
