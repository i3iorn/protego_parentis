package dev.schrammel.protego_parentis.sync

import android.content.Context
import dev.schrammel.protego_parentis.audit.AuditReplication
import dev.schrammel.protego_parentis.enforcement.RequestNotifier
import dev.schrammel.protego_parentis.model.Heartbeat
import dev.schrammel.protego_parentis.policy.IntegrityRules
import dev.schrammel.protego_parentis.sync.SyncWireFields.APP_SEPARATOR
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_ALLOWED
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_APPS_JSON
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_DIAGNOSTICS_LOG
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_APP_PREFIX
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_ARRIVED
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_BATTERY
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_ACCURACY
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_BLOCKED_COUNT
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_BONUS
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_DOMAIN_PREFIX
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_EMERGENCY
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_HISTORY_COUNT
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_HISTORY_HEAD
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_LATITUDE
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_LEVEL
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_LONGITUDE
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_MANAGED
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_PACKAGE
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_PLACE
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_POLICY_VERSION
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_REMAINING
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_SUSPENDED
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_TARGET_LABEL
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_USAGE_ACCESS
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_USED
import dev.schrammel.protego_parentis.sync.SyncWireFields.MAX_SUMMARY_APPS
import dev.schrammel.protego_parentis.sync.SyncWireFields.MAX_SUMMARY_DOMAINS
import dev.schrammel.protego_parentis.sync.SyncWireFields.MAX_WIRE_TEXT
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.sync.core.SyncMessage

/**
 * Everything a parent's phone remembers about what the child is doing:
 * heartbeats, newly-installed apps, usage summaries, shutdown notices,
 * location, place transitions, SOS, web activity, and the app inventory a
 * rule editor offers apps from. Grouped together because they share the same
 * shape — "a fact arrived, file it, maybe notify" — and the same four
 * storage collaborators, not because any one of them depends on another.
 */
internal class TelemetryInboundHandler(
    context: Context,
    private val heartbeats: HeartbeatStore,
    private val newApps: NewAppStore,
    private val summaries: DailySummaryStore,
    private val family: FamilyEventStore,
    private val notifier: RequestNotifier,
) {
    private val appContext = context.applicationContext

    /**
     * The child's phone said it was switching off.
     *
     * Recorded as a very old heartbeat rather than as its own state: the parent's
     * screen already knows how to say "we have not heard from this phone", and a
     * shutdown is exactly that with a reason attached.
     */
    fun rememberStopping(message: SyncMessage): Boolean {
        heartbeats.recordStopped(message.sentAt)
        return true
    }

    fun rememberHeartbeat(message: SyncMessage): Boolean {
        val arrived = Heartbeat(
            sentAt = message.sentAt,
            policyVersion = message.longField(FIELD_POLICY_VERSION) ?: 0L,
            batteryPercent = message.longField(FIELD_BATTERY)?.toInt() ?: -1,
            managed = message.field(FIELD_MANAGED).toBoolean(),
            usageAccessGranted = message.field(FIELD_USAGE_ACCESS).toBoolean(),
            suspendedCount = message.longField(FIELD_SUSPENDED)?.toInt() ?: 0,
            historyHead = message.field(FIELD_HISTORY_HEAD).orEmpty().take(MAX_WIRE_TEXT),
            historyCount = message.longField(FIELD_HISTORY_COUNT) ?: 0L,
        )

        // Checked before the new heartbeat replaces the old, because the judgement
        // is about the pair. Once noticed it stays noticed: a rewritten history is
        // not undone by the next honest entry, and clearing it silently would
        // hand back exactly what the check was for.
        // The third argument is evidence this phone holds rather than something
        // the reporting phone said: entries it wrote that arrived here through
        // the append-only audit section, which it cannot revise from over there.
        val heldFromDevice = runCatching {
            AuditReplication(appContext).entries().count { it.by == message.sender }.toLong()
        }.getOrDefault(0L)

        if (!heartbeats.historySuspect && IntegrityRules.historyRewritten(heartbeats.latest, arrived, heldFromDevice)) {
            heartbeats.historySuspect = true
            // The moment this is set is the only moment worth announcing —
            // heartbeats arrive every 15-60 seconds and the flag never clears
            // once raised (see the comment above), so without the guard this
            // would fire on every single tick from here on.
            notifier.notifyAuditTamper()
        }

        heartbeats.latest = arrived
        // The device has spoken, so the next stretch of silence gets its own alert.
        heartbeats.silenceAlerted = false
        return true
    }

    fun rememberNewApp(message: SyncMessage, senderId: DeviceId): Boolean {
        val packageName = message.field(FIELD_PACKAGE) ?: return false
        val app = NewApp(
            packageName = packageName.clip(),
            label = (message.field(FIELD_TARGET_LABEL) ?: packageName).clip(),
            noticedAt = message.sentAt,
        )
        newApps.remember(app)
        notifier.notifyNewApp(app)
        // Keeps a rule editor's app picker (AppPickerActivity) from silently
        // going stale between full syncs: without this, an app installed
        // after the one time a parent requested the list stayed invisible to
        // every "choose apps" screen indefinitely, with nothing to say the
        // list was out of date. Only once a full inventory already exists —
        // a lone install notice must never look like the whole inventory to
        // a picker that has never asked for one yet, which would hide the
        // one thing (the "request list" prompt) that fixes an empty picker.
        val inventory = AppInventoryStore(appContext)
        if (inventory.receivedAt(senderId) != null) {
            inventory.upsert(senderId, AppInventoryEntry(app.packageName, app.label))
        }
        return true
    }

    /**
     * A summary from a phone still on the older protocol.
     *
     * Folded into replicated telemetry, so a parent's dashboard has one source
     * whichever build the child is on. A newer child writes the same registers
     * itself and this path is never reached for it.
     */
    fun rememberSummary(message: SyncMessage): Boolean {
        val allowed = message.longField(FIELD_ALLOWED)?.toInt() ?: 0
        val bonus = message.longField(FIELD_BONUS)?.toInt() ?: 0
        val used = message.longField(FIELD_USED)?.toInt() ?: 0

        TelemetryReplication(appContext).report(
            usedMinutes = used,
            allowedMinutes = allowed,
            bonusMinutes = bonus,
            // Older senders didn't send this; fall back to the old (slightly
            // lossy) computation rather than dropping the message.
            remainingMinutes = message.longField(FIELD_REMAINING)?.toInt()
                ?: (allowed + bonus - used).coerceAtLeast(0),
            level = message.longField(FIELD_LEVEL)?.toInt() ?: 0,
            topApps = message.fields
                .filterKeys { it.startsWith(FIELD_APP_PREFIX) }
                .toSortedMap(compareBy { it.removePrefix(FIELD_APP_PREFIX).toIntOrNull() ?: 0 })
                .values
                .mapNotNull(::parseAppTime)
                .take(MAX_SUMMARY_APPS),
        )
        summaries.markHeard(message.sentAt)
        return true
    }

    fun rememberLocation(message: SyncMessage): Boolean {
        val latitude = message.field(FIELD_LATITUDE)?.toDoubleOrNull() ?: return false
        val longitude = message.field(FIELD_LONGITUDE)?.toDoubleOrNull() ?: return false
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return false

        family.recordLocation(
            latitude = latitude,
            longitude = longitude,
            accuracyMetres = message.longField(FIELD_ACCURACY)?.toInt() ?: 0,
            at = message.sentAt,
        )
        if (message.field(FIELD_EMERGENCY).toBoolean()) notifier.notifySos(moved = true)
        return true
    }

    fun rememberTransition(message: SyncMessage): Boolean {
        val place = message.field(FIELD_PLACE)?.clip() ?: return false
        val arrived = message.field(FIELD_ARRIVED).toBoolean()

        family.recordTransition(place, arrived, message.sentAt)
        notifier.notifyPlaceTransition(place, arrived)
        return true
    }

    fun rememberSos(message: SyncMessage, raised: Boolean): Boolean {
        family.recordSos(raised, message.sentAt)
        if (raised) notifier.notifySos(moved = false) else notifier.notifySosCleared()
        return true
    }

    fun rememberWebSummary(message: SyncMessage): Boolean {
        val domains = message.fields
            .filterKeys { it.startsWith(FIELD_DOMAIN_PREFIX) }
            .toSortedMap(compareBy { it.removePrefix(FIELD_DOMAIN_PREFIX).toIntOrNull() ?: 0 })
            .values
            .mapNotNull(::parseDomain)
            .take(MAX_SUMMARY_DOMAINS)

        family.recordWebSummary(
            domains = domains,
            blocked = message.longField(FIELD_BLOCKED_COUNT)?.toInt() ?: 0,
            at = message.sentAt,
        )
        return true
    }

    /** Caches which apps [senderId]'s phone can launch, for a rule editor to offer. */
    fun rememberAppInventory(message: SyncMessage, senderId: DeviceId): Boolean {
        AppInventoryStore(appContext).setApps(senderId, AppInventoryCodec.decode(message.field(FIELD_APPS_JSON)))
        return true
    }

    /** Caches [senderId]'s diagnostic log, for a parent to read without holding that phone. */
    fun rememberDiagnosticsReport(message: SyncMessage, senderId: DeviceId): Boolean {
        RemoteDiagnosticsStore(appContext).setLog(senderId, message.field(FIELD_DIAGNOSTICS_LOG).orEmpty())
        return true
    }

    /**
     * The "as of" moment on a parent's dashboard means "when I last heard",
     * recorded here rather than derived from replicated ops, which carry a
     * logical clock and no wall-clock time at all. Called from
     * [ReplicaMaintainer]'s `onStateChanged` whenever a telemetry op just arrived.
     */
    fun markSummaryHeard() {
        summaries.markHeard()
    }

    private fun parseDomain(raw: String): Pair<String, Int>? {
        val (count, label) = parseCountedField(raw) ?: return null
        return label to count
    }

    private fun parseAppTime(raw: String): AppTime? {
        val (minutes, label) = parseCountedField(raw) ?: return null
        return AppTime(label = label, minutes = minutes)
    }

    /**
     * The shared "count:label" shape [FIELD_DOMAIN_PREFIX] and [FIELD_APP_PREFIX]
     * entries both use, split once rather than in each caller.
     */
    private fun parseCountedField(raw: String): Pair<Int, String>? {
        val at = raw.indexOf(APP_SEPARATOR)
        if (at <= 0 || at == raw.lastIndex) return null
        val count = raw.substring(0, at).toIntOrNull() ?: return null
        return count to raw.substring(at + 1).clip()
    }
}
