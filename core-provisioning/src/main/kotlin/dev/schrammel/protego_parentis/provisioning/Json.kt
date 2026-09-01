package dev.schrammel.protego_parentis.provisioning

/**
 * A minimal JSON object writer.
 *
 * Hand-rolled rather than pulled in as a dependency: the provisioning payload is
 * the one thing that must serialise identically on every machine that generates a
 * QR code, and it is a flat map of strings and booleans. Insertion order is
 * preserved so the output is byte-stable and can be diffed in tests.
 */
internal object Json {

    fun obj(entries: Map<String, Any?>): String =
        entries.entries
            .filter { it.value != null }
            .joinToString(separator = ",", prefix = "{", postfix = "}") { (key, value) ->
                "${string(key)}:${render(value!!)}"
            }

    private fun render(value: Any): String = when (value) {
        is String -> string(value)
        is Boolean -> value.toString()
        is Int, is Long -> value.toString()
        is Map<*, *> -> obj(value.entries.associate { (k, v) -> k.toString() to v })
        else -> string(value.toString())
    }

    fun string(raw: String): String = buildString(raw.length + 2) {
        append('"')
        for (char in raw) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }
}
