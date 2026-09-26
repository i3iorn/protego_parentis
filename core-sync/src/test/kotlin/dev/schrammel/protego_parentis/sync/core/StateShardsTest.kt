package dev.schrammel.protego_parentis.sync.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The replica stored a section at a time (#291), so one change rewrites one section. */
class StateShardsTest {

    private fun entry(value: String?, lamport: Long = 1L) = StateEntry(value, lamport, "AAAA")

    private val state = mapOf(
        "chat/1-a@AAAA/text" to entry("hi"),
        "chat/1-a@AAAA/by" to entry("AAAA"),
        "audit/2-b@AAAA/action" to entry("LOCKED", lamport = 2),
        "policy/x/limit" to entry(null, lamport = 3),
    )

    @Test
    fun `a section round-trips on its own`() {
        val encoded = StateShards.encode(state, setOf("chat"))

        assertEquals(setOf("chat"), encoded.keys)
        assertEquals(state.filterKeys { it.startsWith("chat/") }, StateShards.decodeSection(encoded.getValue("chat")))
    }

    @Test
    fun `every section together rebuilds the whole replica, tombstones included`() {
        val all = StateShards.encode(state, StateShards.sectionsOf(state.keys))

        val rebuilt = all.values.flatMap { StateShards.decodeSection(it).entries }.associate { it.key to it.value }

        assertEquals(state, rebuilt)
    }

    @Test
    fun `a section with nothing left in it encodes as absent`() {
        assertNull(StateShards.encode(state, setOf("requests")).getValue("requests"))
    }

    @Test
    fun `sections are a path's first segment`() {
        assertEquals(setOf("chat", "audit"), StateShards.sectionsOf(listOf("chat/1/text", "audit/2/x", "chat/3/by")))
    }
}
