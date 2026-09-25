package dev.schrammel.protego_parentis.rules

import android.content.Context
import dev.schrammel.protego_parentis.model.ChildId
import dev.schrammel.protego_parentis.model.Policy
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.ZoneId

/**
 * What a phone believes before anybody has set anything.
 *
 * This class exists because of one defect: [Policy] declared
 * `tamperProtection = true` while the store read it with a default of `false`.
 * The store is what actually runs, so every fresh install carried none of the
 * lockdown — including the restriction that stops the app's own data being
 * cleared — while anyone reading the model would have concluded the opposite.
 *
 * Nothing would have caught that. The model's default is exercised by the
 * pure-JVM tests next door, the store's default was exercised by nothing at
 * all, and the two are only wrong *in relation to each other*. So the assertion
 * that matters here is not "the default is true" but "the two agree", which is
 * what the tests below actually compare.
 */
@RunWith(RobolectricTestRunner::class)
class LocalPolicyStoreDefaultsTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun store() = LocalPolicyStore(context)

    @Test
    fun `tamper protection defaults to what the model says it defaults to`() {
        val fromTheModel = Policy(
            version = 1L,
            childId = ChildId("any"),
            timeZone = ZoneId.of("UTC"),
        ).tamperProtection

        assertEquals(fromTheModel, store().load().tamperProtection)
    }

    /**
     * Stated separately from the comparison above, because the comparison would
     * still pass if both moved to `false` together — and `false` is the value
     * that leaves a provisioned phone four taps from being handed over.
     */
    @Test
    fun `a phone nobody has configured is protected`() {
        assertEquals(true, store().load().tamperProtection)
    }

    @Test
    fun `turning it off is remembered`() {
        store().tamperProtection = false

        assertEquals(false, LocalPolicyStore(context).load().tamperProtection)
    }

    @Test
    fun `turning it back on is remembered`() {
        store().tamperProtection = false
        store().tamperProtection = true

        assertEquals(true, LocalPolicyStore(context).load().tamperProtection)
    }

    /**
     * [LocalPolicyStore.version], [LocalPolicyStore.timeZone] and
     * [LocalPolicyStore.dayBoundaryHour] exist so the poll's version gate and
     * the grant/visit paths don't build a whole [Policy] for one field. They
     * are only safe to use if they always say what [LocalPolicyStore.load]
     * would — the same "the two agree" property as above.
     */
    @Test
    fun `the single-field getters agree with the assembled policy`() {
        val store = store()
        assertEquals(store.load().version, store.version)

        store.dailyLimitMinutes = 45
        val loaded = store.load()

        assertEquals(loaded.version, store.version)
        assertEquals(loaded.timeZone, store.timeZone)
        assertEquals(loaded.dayBoundaryHour, store.dayBoundaryHour)
    }
}
