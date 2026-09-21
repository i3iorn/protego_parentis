package dev.schrammel.protego_parentis.sync

import android.content.Context
import androidx.core.content.edit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The roster is read off disk on every screen that shows a family member, and
 * the JSON it reads was written by whichever build last saved it — which, in a
 * family mid-rollout, can be a build newer than this one. [SyncRole.parse]
 * exists so a role this build does not have a case for is dropped rather than
 * thrown on; this proves that promise for the file itself, not just the enum.
 *
 * Written straight to the `SharedPreferences` file rather than through
 * [RosterStore]'s own write path, since that path cannot produce a role this
 * build does not recognise — the whole point is a row a *different* build wrote.
 */
@RunWith(RobolectricTestRunner::class)
class RosterStoreTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun store() = RosterStore(context)

    private fun seed(known: String? = null, pending: String? = null) {
        context.getSharedPreferences("roster", Context.MODE_PRIVATE).edit {
            known?.let { putString("known", it) }
            pending?.let { putString("pending", it) }
        }
    }

    @Test
    fun `a known device with a role this build does not recognise is dropped, not the whole roster`() {
        seed(
            known = """[
                {"id":"a","name":"Mum's phone","role":"PARENT","publicKey":"key-a","approvedAt":1000},
                {"id":"b","name":"A future role","role":"GRANDPARENT","publicKey":"key-b","approvedAt":2000}
            ]""",
        )

        val devices = store().devices

        assertEquals(listOf("a"), devices.map { it.id.value })
    }

    @Test
    fun `a pending device with a role this build does not recognise is dropped, not the whole list`() {
        seed(
            pending = """[
                {"id":"a","name":"A phone","role":"CHILD","publicKey":"key-a","firstHeardAt":1000},
                {"id":"b","name":"Newer sibling role","role":"GRANDPARENT","publicKey":"key-b","firstHeardAt":2000}
            ]""",
        )

        val pending = store().pending

        assertEquals(listOf("a"), pending.map { it.id.value })
    }

    @Test
    fun `a roster with only unrecognised roles is empty, not a crash`() {
        seed(
            known = """[{"id":"a","name":"Newer only","role":"GRANDPARENT","publicKey":"key-a","approvedAt":1000}]""",
        )

        assertTrue(store().devices.isEmpty())
    }
}
