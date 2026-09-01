package dev.schrammel.protego_parentis.rules

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoredCategoryTest {

    private fun category() = StoredCategory(
        id = "games",
        name = "Games",
        limitMinutes = 60,
        packages = setOf("com.example.a", "com.example.b"),
        subcategories = setOf("puzzle"),
    )

    @Test
    fun `round-trips through json`() {
        val original = category()

        assertEquals(original, StoredCategory.fromJson(original.toJson()))
    }

    @Test
    fun `round-trips a whole list, including the empty list`() {
        val categories = listOf(category(), category().copy(id = "social", name = "Social"))

        assertEquals(categories, StoredCategory.listFromJson(StoredCategory.listToJson(categories)))
        assertEquals(emptyList<StoredCategory>(), StoredCategory.listFromJson(StoredCategory.listToJson(emptyList())))
    }

    @Test
    fun `defaults to no packages and no subcategories when absent`() {
        val json = JSONObject().apply {
            put("id", "games")
            put("name", "Games")
            put("limitMinutes", 30)
        }

        assertEquals(
            StoredCategory(id = "games", name = "Games", limitMinutes = 30, packages = emptySet()),
            StoredCategory.fromJson(json),
        )
    }

    @Test
    fun `defaults the limit to zero when absent`() {
        val json = JSONObject().apply {
            put("id", "games")
            put("name", "Games")
        }

        assertEquals(0, StoredCategory.fromJson(json)?.limitMinutes)
    }

    @Test
    fun `refuses a row with no id`() {
        val json = JSONObject().apply { put("name", "Games") }

        assertNull(StoredCategory.fromJson(json))
    }

    @Test
    fun `refuses a row with a blank name`() {
        val json = JSONObject().apply {
            put("id", "games")
            put("name", "   ")
        }

        assertNull(StoredCategory.fromJson(json))
    }

    @Test
    fun `blank entries in a package or subcategory array are dropped`() {
        val json = JSONObject(
            """{"id":"games","name":"Games","limitMinutes":0,"packages":["com.a","","  "],"subcategories":[""]}""",
        )

        val decoded = StoredCategory.fromJson(json)

        assertEquals(setOf("com.a"), decoded?.packages)
        assertTrue(decoded?.subcategories.orEmpty().isEmpty())
    }

    /** One bad row should not take the rest of the family's categories down with it. */
    @Test
    fun `a malformed row is skipped rather than failing the whole list`() {
        val good = category()
        val raw = """[${good.toJson()}, {"name":"missing an id"}, "not even an object"]"""

        assertEquals(listOf(good), StoredCategory.listFromJson(raw))
    }

    @Test
    fun `blank or unparseable input yields no categories`() {
        assertEquals(emptyList<StoredCategory>(), StoredCategory.listFromJson(null))
        assertEquals(emptyList<StoredCategory>(), StoredCategory.listFromJson(""))
        assertEquals(emptyList<StoredCategory>(), StoredCategory.listFromJson("not json"))
    }
}
