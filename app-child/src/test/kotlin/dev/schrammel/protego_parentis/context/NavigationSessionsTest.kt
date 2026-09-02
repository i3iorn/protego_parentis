package dev.schrammel.protego_parentis.context

import dev.schrammel.protego_parentis.model.PackageName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * [NavigationSessions] — what the enforcement coordinator hands the engine as
 * `DeviceState.navigatingPackage`.
 *
 * The engine's own deferral was already covered; nothing covered anything ever
 * producing the state, which is why the feature sat dead in production.
 */
class NavigationSessionsTest {

    private val maps = "com.example.maps"
    private val chat = "com.example.chat"

    @Before
    fun clean() = NavigationSessions.reset()

    @Test
    fun `nothing posted means nothing navigating`() {
        assertNull(NavigationSessions.current())
    }

    @Test
    fun `an ongoing navigation notification is a live route`() {
        NavigationSessions.posted("k1", maps, "navigation", ongoing = true)

        assertEquals(PackageName(maps), NavigationSessions.current())
    }

    @Test
    fun `a navigation alert that is not ongoing is not a route`() {
        NavigationSessions.posted("k1", maps, "navigation", ongoing = false)

        assertNull(NavigationSessions.current())
    }

    @Test
    fun `an ongoing notification in another category is not a route`() {
        NavigationSessions.posted("k1", chat, "msg", ongoing = true)

        assertNull(NavigationSessions.current())
    }

    @Test
    fun `a notification with no category is not a route`() {
        NavigationSessions.posted("k1", maps, null, ongoing = true)

        assertNull(NavigationSessions.current())
    }

    @Test
    fun `removing the notification ends the route`() {
        NavigationSessions.posted("k1", maps, "navigation", ongoing = true)
        NavigationSessions.removed("k1")

        assertNull(NavigationSessions.current())
    }

    /** How a route usually ends: the same notification updated, ongoing dropped. */
    @Test
    fun `an update that drops the ongoing flag ends the route`() {
        NavigationSessions.posted("k1", maps, "navigation", ongoing = true)
        NavigationSessions.posted("k1", maps, "navigation", ongoing = false)

        assertNull(NavigationSessions.current())
    }

    /** Turn-by-turn apps repost constantly; that must not accumulate routes. */
    @Test
    fun `reposting the same notification is still one route`() {
        repeat(5) { NavigationSessions.posted("k1", maps, "navigation", ongoing = true) }

        assertEquals(PackageName(maps), NavigationSessions.current())
    }

    @Test
    fun `one app with two ongoing notifications is still that one app`() {
        NavigationSessions.posted("k1", maps, "navigation", ongoing = true)
        NavigationSessions.posted("k2", maps, "navigation", ongoing = true)

        assertEquals(PackageName(maps), NavigationSessions.current())
    }

    /** Sparing the wrong app is worse than sparing none. */
    @Test
    fun `two apps navigating at once spares neither`() {
        NavigationSessions.posted("k1", maps, "navigation", ongoing = true)
        NavigationSessions.posted("k2", chat, "navigation", ongoing = true)

        assertNull(NavigationSessions.current())
    }

    @Test
    fun `losing the listener connection ends every route`() {
        NavigationSessions.posted("k1", maps, "navigation", ongoing = true)
        NavigationSessions.reset()

        assertNull(NavigationSessions.current())
    }
}
