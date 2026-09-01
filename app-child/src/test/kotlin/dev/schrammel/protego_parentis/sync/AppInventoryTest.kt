package dev.schrammel.protego_parentis.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppInventoryTest {

    @Test
    fun `round-trips a list of apps`() {
        val apps = listOf(
            AppInventoryEntry("com.example.a", "App A"),
            AppInventoryEntry("com.example.b", "App B"),
        )

        assertEquals(apps, AppInventoryCodec.decode(AppInventoryCodec.encode(apps)))
    }

    @Test
    fun `round-trips the empty list`() {
        assertEquals(emptyList<AppInventoryEntry>(), AppInventoryCodec.decode(AppInventoryCodec.encode(emptyList())))
    }

    @Test
    fun `falls back to the package name when the label is blank`() {
        val encoded = AppInventoryCodec.encode(listOf(AppInventoryEntry("com.example.a", "")))

        assertEquals("com.example.a", AppInventoryCodec.decode(encoded).single().label)
    }

    @Test
    fun `an entry with no package name is dropped rather than failing the whole list`() {
        val raw = """[{"l":"No package"},{"p":"com.example.a","l":"App A"}]"""

        val decoded = AppInventoryCodec.decode(raw)

        assertEquals(1, decoded.size)
        assertTrue(decoded.any { it.packageName == "com.example.a" })
    }

    @Test
    fun `blank or unparseable input decodes to an empty list`() {
        assertEquals(emptyList<AppInventoryEntry>(), AppInventoryCodec.decode(null))
        assertEquals(emptyList<AppInventoryEntry>(), AppInventoryCodec.decode(""))
        assertEquals(emptyList<AppInventoryEntry>(), AppInventoryCodec.decode("not json"))
    }
}
