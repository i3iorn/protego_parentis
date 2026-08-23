package dev.schrammel.dementor_watch.rules

import org.json.JSONArray
import org.json.JSONObject

/**
 * A category as persisted on the device.
 *
 * Separate from the domain [dev.schrammel.dementor_watch.model.Category] so the
 * storage format can change without dragging the engine with it. Serialised with
 * `org.json`, which ships with Android — no dependency required for what is a
 * handful of fields.
 */
data class StoredCategory(
    val id: String,
    val name: String,
    val limitMinutes: Int,
    val packages: Set<String>,
    /** Ids of categories nested inside this one. */
    val subcategories: Set<String> = emptySet(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_ID, id)
        put(KEY_NAME, name)
        put(KEY_LIMIT, limitMinutes)
        put(KEY_PACKAGES, JSONArray(packages.toList()))
        put(KEY_SUBCATEGORIES, JSONArray(subcategories.toList()))
    }

    companion object {
        private const val KEY_ID = "id"
        private const val KEY_NAME = "name"
        private const val KEY_LIMIT = "limitMinutes"
        private const val KEY_PACKAGES = "packages"
        private const val KEY_SUBCATEGORIES = "subcategories"

        fun fromJson(json: JSONObject): StoredCategory? {
            val id = json.optString(KEY_ID).takeIf { it.isNotBlank() } ?: return null
            val name = json.optString(KEY_NAME).takeIf { it.isNotBlank() } ?: return null
            fun strings(key: String): List<String> =
                json.optJSONArray(key)?.let { array ->
                    (0 until array.length())
                        .mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
                }.orEmpty()

            val packages = strings(KEY_PACKAGES)
            return StoredCategory(
                id = id,
                name = name,
                limitMinutes = json.optInt(KEY_LIMIT, 0),
                packages = packages.toSet(),
                subcategories = strings(KEY_SUBCATEGORIES).toSet(),
            )
        }

        fun listToJson(categories: List<StoredCategory>): String =
            JSONArray(categories.map(StoredCategory::toJson)).toString()

        /** Skips anything malformed rather than losing every category to one bad row. */
        fun listFromJson(raw: String?): List<StoredCategory> {
            if (raw.isNullOrBlank()) return emptyList()
            val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(::fromJson)
            }
        }
    }
}
