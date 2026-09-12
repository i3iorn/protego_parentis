package dev.schrammel.protego_parentis.diagnostics

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Encoding and rendering for the diagnostic log, with no Android in sight.
 *
 * Split out from [Diagnostics] so the privacy rule can be enforced by a test
 * rather than by remembering it: the shape of a rendered line is checked, so a
 * later change that interpolates a package name or a reason into the log fails
 * the build instead of quietly shipping.
 *
 * Internal: [Diagnostics] is this module's public entry point — a caller
 * outside it wants [Diagnostics.record]/[Diagnostics.asText], never the raw
 * encoding.
 */
internal object DiagnosticLogFormat {

    /** Kept small on purpose: enough to see a pattern, not enough to be a history. */
    const val MAX_ENTRIES = 300

    private const val ENTRY_SEPARATOR = "\n"
    private const val FIELD_SEPARATOR = "|"
    private const val COUNT_SEPARATOR = ";"

    /**
     * Every rendered line must match this: a timestamp, an event name in capitals,
     * and zero or more `name=number` pairs. No other shape is permitted, which is
     * what stops free text finding its way in.
     */
    val RENDERED_LINE = Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} {2}[A-Z_]+( {2}([a-zA-Z]+=-?\d+ ?)+)?$""")

    private fun timestamp(zone: ZoneId): DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zone)

    fun encode(entry: DiagnosticEntry): String {
        val counts = entry.counts.entries.joinToString(COUNT_SEPARATOR) { "${it.key}=${it.value}" }
        return listOf(entry.at.toEpochMilli(), entry.event.name, counts)
            .joinToString(FIELD_SEPARATOR)
    }

    /** Null for anything malformed, so one bad line never loses the rest. */
    fun decode(line: String): DiagnosticEntry? {
        val parts = line.split(FIELD_SEPARATOR)
        if (parts.size < 2) return null

        val at = parts[0].toLongOrNull() ?: return null
        val event = DiagnosticEvent.entries.firstOrNull { it.name == parts[1] } ?: return null

        val counts = parts.getOrNull(2)
            ?.split(COUNT_SEPARATOR)
            ?.filter(String::isNotBlank)
            ?.mapNotNull { pair ->
                val split = pair.indexOf('=')
                if (split <= 0) return@mapNotNull null
                val value = pair.substring(split + 1).toLongOrNull() ?: return@mapNotNull null
                pair.substring(0, split) to value
            }
            ?.toMap()
            .orEmpty()

        return DiagnosticEntry(Instant.ofEpochMilli(at), event, counts)
    }

    fun encodeAll(entries: List<DiagnosticEntry>): String =
        entries.takeLast(MAX_ENTRIES).joinToString(ENTRY_SEPARATOR, transform = ::encode)

    fun decodeAll(raw: String?): List<DiagnosticEntry> =
        raw?.split(ENTRY_SEPARATOR)?.filter(String::isNotBlank)?.mapNotNull(::decode).orEmpty()

    /** Appends one entry, keeping the log within [MAX_ENTRIES]. */
    fun append(raw: String?, entry: DiagnosticEntry): String =
        encodeAll(decodeAll(raw) + entry)

    /** One human-readable line per entry. */
    fun renderLine(entry: DiagnosticEntry, zone: ZoneId = ZoneId.systemDefault()): String =
        buildString {
            append(timestamp(zone).format(entry.at))
            append("  ")
            append(entry.event.name)
            if (entry.counts.isNotEmpty()) {
                append("  ")
                append(entry.counts.entries.joinToString(" ") { "${it.key}=${it.value}" })
            }
        }

    fun render(
        entries: List<DiagnosticEntry>,
        header: String,
        empty: String,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = buildString {
        appendLine(header)
        appendLine()
        if (entries.isEmpty()) {
            appendLine(empty)
            return@buildString
        }
        entries.forEach { appendLine(renderLine(it, zone)) }
    }
}
