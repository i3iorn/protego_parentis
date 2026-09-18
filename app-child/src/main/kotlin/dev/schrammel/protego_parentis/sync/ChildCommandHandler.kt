package dev.schrammel.protego_parentis.sync

import android.content.Context
import android.util.Log
import dev.schrammel.protego_parentis.AppRegistry
import dev.schrammel.protego_parentis.audit.Audit
import dev.schrammel.protego_parentis.context.LocationSharing
import dev.schrammel.protego_parentis.diagnostics.DiagnosticEvent
import dev.schrammel.protego_parentis.diagnostics.Diagnostics
import dev.schrammel.protego_parentis.enforcement.EnforcementService
import dev.schrammel.protego_parentis.enforcement.RequestNotifier
import dev.schrammel.protego_parentis.model.AuditAction
import dev.schrammel.protego_parentis.model.AuditActor
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.Place
import dev.schrammel.protego_parentis.model.PlaceId
import dev.schrammel.protego_parentis.rules.AppReviewStore
import dev.schrammel.protego_parentis.rules.ContextRuleStore
import dev.schrammel.protego_parentis.rules.Feature
import dev.schrammel.protego_parentis.rules.FeatureFlags
import dev.schrammel.protego_parentis.rules.GrantStore
import dev.schrammel.protego_parentis.rules.LocalPolicyStore
import dev.schrammel.protego_parentis.rules.PolicySnapshot
import dev.schrammel.protego_parentis.rules.TargetLabels
import dev.schrammel.protego_parentis.rules.applySnapshot
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_BLOCKED
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_LATITUDE
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_LONGITUDE
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_MINUTES
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_PACKAGE
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_PLACE
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_POLICY_JSON
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_RADIUS
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_TARGET
import dev.schrammel.protego_parentis.sync.core.SyncMessage
import dev.schrammel.protego_parentis.usage.InstalledApps
import java.time.Instant
import java.util.UUID

/**
 * What the child's phone does when a parent tells it something directly —
 * lock now, answer a locate, create a place, decide on an app, grant bonus
 * time, or replace the whole rule set. Every one of these only ever reaches
 * the child side of [SyncReceiver.applyAsChild]; splitting them out from the
 * device-trust and telemetry families they used to sit beside keeps "a
 * parent changed something about this phone's policy" as one place to read.
 */
internal class ChildCommandHandler(
    context: Context,
    private val publisher: SyncPublisher,
    private val notifier: RequestNotifier,
) {
    private val appContext = context.applicationContext

    /**
     * Locks the screen, and says who did it.
     *
     * The notification is posted first so it lands on the lock screen the child is
     * about to see. A phone that locks itself with no explanation is a phone that
     * looks broken, and the answer to "why did that happen" should not require
     * unlocking it again to find out.
     */
    fun applyLockNow(): Boolean {
        notifier.notifyLocked()
        Audit.record(appContext, AuditActor.PARENT, AuditAction.DEVICE_LOCKED)
        // Handed to the service rather than done here, and not through a callback
        // either: any screen can be the one that happens to be polling when this
        // arrives, and a lock that only worked when the service polled would fail
        // exactly when a parent was watching the app on their own phone.
        EnforcementService.lockNow(appContext)
        return true
    }

    /**
     * Answers a locate, and tells the child it was asked.
     *
     * The telling is not conditional on the answering. A locate that failed
     * because the phone was indoors still happened, and a child who is told only
     * about the successful ones has been told something misleading about how often
     * they are looked for.
     */
    fun applyLocateRequest(): Boolean {
        // Told either way. A locate that was refused still happened, and a child
        // who hears only about the answered ones has been told something
        // misleading about how often they are looked for.
        notifier.notifyLocated()
        Audit.record(appContext, AuditActor.PARENT, AuditAction.LOCATE_REQUESTED)

        // Turned off means turned off, not hidden. A parent who put this away and
        // found their old phone still answering would be right to feel misled.
        // Every branch below answers. A locate that produced nothing used to
        // write a line to logcat and stop there, so the parent saw "Sent" and
        // then waited on something that was never coming — indistinguishable
        // from an answer still in flight.
        val telemetry = TelemetryReplication(appContext)

        if (!FeatureFlags(appContext).isEnabled(Feature.LOCATION)) {
            Log.i(TAG, "Asked for a position while locating is switched off")
            telemetry.reportLocate(LocateOutcome.SWITCHED_OFF)
            publisher.publishPendingOps()
            return true
        }

        val position = LocationSharing(appContext).locate()
        if (position == null) {
            Log.i(TAG, "Asked for a position, could not get one")
            telemetry.reportLocate(LocateOutcome.UNAVAILABLE)
            publisher.publishPendingOps()
            return true
        }

        Audit.record(appContext, AuditActor.CHILD, AuditAction.LOCATION_SHARED)
        publisher.publishLocation(position, forEmergency = false)
        telemetry.reportLocate(LocateOutcome.ANSWERED)
        publisher.publishPendingOps()
        return true
    }

    /**
     * Materialises a place a parent named from a coordinate their own phone
     * supplied — typed by hand, or that phone's own position — since nothing
     * about [dev.schrammel.protego_parentis.ui.rules.PlaceRuleStore]'s
     * rename/resize/delete ever hands it one to create with — see
     * [dev.schrammel.protego_parentis.sync.core.SyncMessageType.PLACE_CREATE_REQUESTED].
     * Validated the same way [dev.schrammel.protego_parentis.ui.PlacesActivity]'s
     * own dialogs validate one typed or captured locally, since a coordinate
     * arriving over the wire deserves no more trust than one typed by a stranger.
     */
    fun applyPlaceCreateRequest(message: SyncMessage): Boolean {
        val name = message.field(FIELD_PLACE)?.clip()?.takeIf(String::isNotBlank) ?: return false
        val latitude = message.field(FIELD_LATITUDE)?.toDoubleOrNull() ?: return false
        val longitude = message.field(FIELD_LONGITUDE)?.toDoubleOrNull() ?: return false
        val radiusMetres = message.longField(FIELD_RADIUS)?.toInt() ?: return false
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return false
        if (radiusMetres < Place.MINIMUM_RADIUS_METRES) return false

        ContextRuleStore(appContext).upsertPlace(
            Place(
                id = PlaceId(UUID.randomUUID().toString()),
                name = name,
                latitude = latitude,
                longitude = longitude,
                radiusMetres = radiusMetres,
            ),
        )
        // So every parent phone sees it next time places replicate, the same
        // path a place created by hand on this device already takes.
        PolicyReplication(appContext).pushPlaceRefsFromLocal()
        Audit.record(
            context = appContext,
            actor = AuditActor.PARENT,
            action = AuditAction.PLACE_ADDED,
            subject = name,
        )
        AppRegistry.ruleMutationSink.rulesChanged()
        notifier.notifyRuleChanged()
        return true
    }

    /**
     * Applies a parent's answer about a new app.
     *
     * Marking it reviewed either way is the point: an allowed app has to stop
     * showing up as new, or the parent is asked the same question forever.
     */
    fun applyAppDecision(message: SyncMessage): Boolean {
        val packageName = message.field(FIELD_PACKAGE) ?: return false
        val blocked = message.field(FIELD_BLOCKED)?.toBoolean() ?: return false

        val label = InstalledApps(appContext).labelFor(PackageName(packageName))
        LocalPolicyStore(appContext).setBlocked(packageName, blocked)
        AppReviewStore(appContext).markReviewed(packageName)
        Audit.record(
            context = appContext,
            actor = AuditActor.PARENT,
            action = AuditAction.APP_BLOCK_CHANGED,
            subject = label,
            after = Audit.flag(blocked),
        )
        AppRegistry.ruleMutationSink.rulesChanged()
        // The app sat in "new arrivals" waiting on this; without a notification
        // the only way to learn it was reviewed is to notice it now works (or
        // does not) and go looking for why.
        notifier.notifyAppDecided(label, blocked)
        return true
    }

    /**
     * Applies bonus time sent by a parent phone.
     *
     * Granting and re-evaluating at once is the point: a parent who taps
     * "+15 minutes" and sees nothing change on the child's phone stops trusting
     * the feature within a day.
     */
    fun applyBonus(message: SyncMessage): Boolean {
        val minutes = message.longField(FIELD_MINUTES)?.toInt()?.takeIf { it > 0 } ?: return false
        val target = SyncTargets.decode(message.field(FIELD_TARGET)) ?: return false

        val policy = LocalPolicyStore(appContext).load()
        GrantStore(appContext).add(
            target = target,
            minutes = minutes,
            now = Instant.now(),
            zone = policy.timeZone,
            dayBoundaryHour = policy.dayBoundaryHour,
        )
        Audit.record(
            context = appContext,
            actor = AuditActor.PARENT,
            action = AuditAction.BONUS_GRANTED,
            subject = TargetLabels.describe(appContext, target),
            after = Audit.minutes(minutes),
        )
        AppRegistry.ruleMutationSink.rulesChanged()
        // Otherwise the only way to notice extra time arrived is to stumble on
        // it — the same "granted and re-evaluated at once" reasoning as this
        // method's own doc comment, just for the person rather than the policy.
        notifier.notifyBonusGranted(minutes, TargetLabels.describe(appContext, target))
        return true
    }

    /**
     * Replaces every remotely-editable rule at once.
     *
     * Refused if [PolicySnapshot.version] does not match this phone's current
     * one: the parent's copy was taken at that version, and applying a push
     * based on stale rules would silently discard whatever changed here since
     * — a schedule added, a limit lifted, anything at all — without either side
     * knowing it happened.
     */
    fun applyPolicyUpdate(message: SyncMessage): Boolean {
        val incoming = PolicySnapshot.decode(message.field(FIELD_POLICY_JSON)) ?: return false
        val store = LocalPolicyStore(appContext)

        if (store.load().version != incoming.version) {
            Diagnostics.record(appContext, DiagnosticEvent.POLICY_UPDATE_CONFLICT)
            return false
        }

        store.applySnapshot(incoming)
        // Also recorded as ops, so the change reaches every device on the newer
        // protocol rather than only the two ends of this legacy exchange.
        PolicyReplication(appContext).pushFromLocalPolicy()
        Audit.record(
            context = appContext,
            actor = AuditActor.PARENT,
            action = AuditAction.RULES_REPLACED,
        )
        AppRegistry.ruleMutationSink.rulesChanged()
        return true
    }

    private companion object {
        const val TAG = "DementorSync"
    }
}
