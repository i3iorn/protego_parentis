package dev.schrammel.dementor_watch.enforcement

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.schrammel.dementor_watch.R
import dev.schrammel.dementor_watch.diagnostics.DiagnosticEvent
import dev.schrammel.dementor_watch.diagnostics.Diagnostics
import android.os.BatteryManager
import dev.schrammel.dementor_watch.model.BudgetLevel
import dev.schrammel.dementor_watch.model.Heartbeat
import dev.schrammel.dementor_watch.model.PackageName
import dev.schrammel.dementor_watch.rules.AppReviewStore
import dev.schrammel.dementor_watch.rules.GoalCoordinator
import dev.schrammel.dementor_watch.security.DeviceRole
import dev.schrammel.dementor_watch.security.RoleStore
import dev.schrammel.dementor_watch.usage.InstalledApps
import dev.schrammel.dementor_watch.sync.SyncCoordinator
import dev.schrammel.dementor_watch.ui.Format
import dev.schrammel.dementor_watch.ui.HomeActivity
import java.time.Instant

/**
 * Keeps enforcement alive and re-evaluates on a tick.
 *
 * Runs in the foreground with a permanent notification, which is required twice
 * over: Android's background limits would otherwise kill it, and Play's policy
 * requires a monitored device to visibly show that it is monitored. The
 * notification is not dismissible, and that is deliberate.
 *
 * The re-evaluation interval is driven by the engine's own `nextChangeAt` rather
 * than a fixed poll — see [delayUntil].
 */
class EnforcementService : Service() {

    /**
     * The tick runs here, not on the main looper.
     *
     * One pass replays a day of usage events, walks the installed-app list, drives
     * DevicePolicyManager and writes several preference files. On the main thread
     * that blocked every screen in the app and risked a foreground-service ANR.
     */
    private val worker = HandlerThread("dementor-enforcement").apply { start() }
    private val handler = Handler(worker.looper)
    private lateinit var coordinator: EnforcementCoordinator
    private lateinit var warnings: WarningNotifier
    private lateinit var goals: GoalCoordinator
    private lateinit var goalNotifier: GoalNotifier
    private lateinit var sync: SyncCoordinator
    private lateinit var review: AppReviewStore
    private lateinit var installedApps: InstalledApps
    private lateinit var requestNotifier: RequestNotifier

    /**
     * Fires when the device is unlocked. Registered at runtime because implicit
     * broadcasts of this kind cannot be declared in the manifest.
     */
    private val onUnlocked = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_USER_PRESENT) return
            // A broadcast arrives on the main thread, and checking goals reads
            // history off disk. Hand it to the worker rather than block the unlock.
            handler.post {
                runCatching { goals.refresh() }
                    .onSuccess { earned -> earned?.let(goalNotifier::notifyEarned) }
                    .onFailure { Log.w(TAG, "Could not check goals on unlock", it) }
            }
        }
    }

    /**
     * Fires when a package is installed or removed.
     *
     * Registered at runtime rather than in the manifest: since Android 8 a
     * manifest-declared receiver does not get `ACTION_PACKAGE_ADDED` at all, and
     * this service is always running on a child device anyway.
     */
    private val onPackagesChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Replacing an app is an update, not an arrival. Reporting an update
            // as a new install would flag half the phone after a Play Store sweep.
            if (intent?.getBooleanExtra(Intent.EXTRA_REPLACING, false) == true) return
            handler.post { runCatching { sweepForNewApps() } }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            // An exception escaping here would kill the Runnable and stop
            // enforcement permanently, with the notification still claiming the
            // rules are active. Always reschedule, whatever happened.
            val nextIn = try {
                evaluate()
            } catch (e: Exception) {
                Log.e(TAG, "Evaluation failed; retrying", e)
                Diagnostics.record(this@EnforcementService, DiagnosticEvent.EVALUATION_FAILED)
                RETRY_INTERVAL_MILLIS
            }
            handler.postDelayed(this, nextIn)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        coordinator = EnforcementCoordinator(this)
        review = AppReviewStore(this)
        installedApps = InstalledApps(this)
        requestNotifier = RequestNotifier(this)
        warnings = WarningNotifier(this)
        goals = GoalCoordinator(this)
        goalNotifier = GoalNotifier(this)
        sync = SyncCoordinator(this)
        createNotificationChannel()
        ContextCompat.registerReceiver(
            this,
            onUnlocked,
            IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            this,
            onPackagesChanged,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Belt and braces with the check in BootReceiver. Several screens start
        // this service, and one of them reaching a parent's phone by mistake must
        // not turn that phone into a second, fictitious child device.
        if (RoleStore(this).roleOrChild != DeviceRole.CHILD) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // FOREGROUND_SERVICE_TYPE_SPECIAL_USE only exists from API 34. On 33 the
        // two-argument call takes the type from the manifest, which is what we want
        // — passing the constant there would throw.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(getString(R.string.service_notification_text)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(getString(R.string.service_notification_text)),
            )
        }

        Diagnostics.record(this, DiagnosticEvent.SERVICE_STARTED)
        handler.removeCallbacks(tick)
        handler.post(tick)

        // START_STICKY so the OS brings us back if the process is reclaimed.
        return START_STICKY
    }

    override fun onDestroy() {
        Diagnostics.record(this, DiagnosticEvent.SERVICE_STOPPED)
        handler.removeCallbacks(tick)
        worker.quitSafely()
        runCatching { unregisterReceiver(onUnlocked) }
        runCatching { unregisterReceiver(onPackagesChanged) }
        super.onDestroy()
    }

    /** Evaluates once and returns how long to wait before doing it again. */
    private fun evaluate(): Long {
        val now = Instant.now()
        val snapshot = coordinator.evaluate(now)
        if (snapshot == null) {
            updateNotification(getString(R.string.service_needs_permission))
            Diagnostics.record(this, DiagnosticEvent.USAGE_ACCESS_MISSING)
            // Still say hello. Without this, revoked usage access is indistinguishable
            // from a phone that has been switched off — and the two want very
            // different responses from a parent.
            runCatching {
                sync.publishHeartbeat(
                    Heartbeat(
                        sentAt = now,
                        policyVersion = 0L,
                        batteryPercent = batteryPercent(),
                        managed = coordinator.isDeviceOwner(),
                        usageAccessGranted = false,
                        suspendedCount = 0,
                    ),
                )
            }.onFailure { Log.w(TAG, "Could not send heartbeat", it) }
            sync.pollNow()
            return MAX_INTERVAL_MILLIS
        }

        warnings.onEvaluated(snapshot.enforcement.deviceBudget, snapshot.usage.snapshot.policyDay)
        updateNotification(summarise(snapshot))

        // Counts only: enough to see that a day is being measured and how much is
        // being enforced, without recording which apps or when they were used.
        Diagnostics.record(
            this,
            DiagnosticEvent.EVALUATED,
            mapOf(
                "usedMinutes" to snapshot.usage.snapshot.deviceTotal.inWholeMinutes,
                "appsWithTime" to snapshot.usage.snapshot.byPackage.size.toLong(),
                "wouldSuspend" to snapshot.enforcement.suspend.size.toLong(),
                "level" to snapshot.enforcement.deviceBudget.level.ordinal.toLong(),
                "managed" to if (snapshot.monitorOnly) 0L else 1L,
            ),
        )

        Log.d(
            TAG,
            "used=${snapshot.usage.snapshot.deviceTotal} " +
                "level=${snapshot.enforcement.deviceBudget.level} " +
                "wouldSuspend=${snapshot.enforcement.suspend.size} " +
                "monitorOnly=${snapshot.monitorOnly}",
        )

        // The broadcast is the fast path; this catches anything installed while
        // the service was not running, which on a rebooting phone is a real window.
        runCatching { sweepForNewApps() }
            .onFailure { Log.w(TAG, "Could not check for new apps", it) }

        // Throttled inside the coordinator: this tick can fire every fifteen
        // seconds when a limit is close, and a heartbeat that often is a battery bug.
        runCatching { sync.publishHeartbeat(heartbeatOf(snapshot, now)) }
            .onFailure { Log.w(TAG, "Could not send heartbeat", it) }

        // Piggybacks on the same tick rather than holding a connection open.
        // Failures are reported through the callback and never reach here.
        sync.pollNow()

        return delayUntil(snapshot.enforcement.nextChangeAt, now)
    }

    /**
     * Notices apps that were not on the phone last time we looked (AC-3).
     *
     * The very first sweep seeds instead of reporting — see [AppReviewStore]. Runs
     * on the worker thread; walking the package list is slow.
     */
    private fun sweepForNewApps() {
        // Not redundant with the cache's own receiver: the two are separate
        // broadcasts with no ordering guarantee between them, and reading a stale
        // list here would miss the app this sweep was woken for.
        InstalledApps.invalidate()

        val installed = installedApps.launchable()
        val fresh = review.noticeInstalled(installed.map { it.pkg.value }.toSet())
        if (fresh.isEmpty()) return

        fresh.forEach { pkg ->
            val label = installedApps.labelFor(PackageName(pkg))
            requestNotifier.notifyNewAppLocally(label)
            sync.publishAppInstalled(pkg, label)
        }
    }

    /**
     * What the child device tells its parents about itself (TR-2).
     *
     * Facts about the app and the phone, never about what was used. The one field
     * that comes close is the count of suspended apps, which says how much is being
     * enforced without saying what.
     */
    private fun heartbeatOf(snapshot: EnforcementSnapshot, now: Instant) = Heartbeat(
        sentAt = now,
        policyVersion = snapshot.policy.version,
        batteryPercent = batteryPercent(),
        managed = !snapshot.monitorOnly,
        // Unconditionally true: a snapshot only exists when usage access is
        // granted. The other branch of evaluate() sends the false case.
        usageAccessGranted = true,
        suspendedCount = snapshot.enforcement.suspend.size,
    )

    /** -1 when the level cannot be read, which the parent's screen shows as unknown. */
    private fun batteryPercent(): Int =
        getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }
            ?: -1

    /**
     * Sleeps until the engine says the decision would change, within bounds.
     *
     * The engine already computes the exact moment a limit bites or a warning is
     * due, so there is no reason to wake every minute. With the screen off and
     * nothing running, the next change is usually the 4am reset and we idle at the
     * maximum interval instead of burning battery on 300 pointless evaluations.
     *
     * Exact alarms would be the theoretical ideal, but `SCHEDULE_EXACT_ALARM` is a
     * restricted permission that Play grants to alarm-clock apps, and a minute of
     * slack on a screen-time warning does not justify that fight.
     */
    private fun delayUntil(nextChange: Instant?, now: Instant): Long {
        if (nextChange == null) return MAX_INTERVAL_MILLIS
        val millis = nextChange.toEpochMilli() - now.toEpochMilli()
        return millis.coerceIn(MIN_INTERVAL_MILLIS, MAX_INTERVAL_MILLIS)
    }

    private fun summarise(snapshot: EnforcementSnapshot): String {
        val budget = snapshot.enforcement.deviceBudget
        return when {
            budget.isUnlimited ->
                getString(R.string.service_no_limit, Format.duration(budget.used))

            budget.level == BudgetLevel.EXHAUSTED ->
                getString(R.string.service_used_up)

            else ->
                getString(R.string.service_remaining, Format.duration(budget.remaining))
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.service_channel_name),
            // LOW: permanently visible, but it must never buzz.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.service_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(getString(R.string.service_notification_title))
        .setContentText(text)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, HomeActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    companion object {
        private const val TAG = "DementorService"
        private const val CHANNEL_ID = "enforcement"
        private const val NOTIFICATION_ID = 1
        /** Never re-evaluate more often than this, however close the next change is. */
        private const val MIN_INTERVAL_MILLIS = 15_000L

        /** Never sleep longer than this, so drift and missed events self-correct. */
        private const val MAX_INTERVAL_MILLIS = 5 * 60_000L

        /** Backs off briefly after a failure rather than hammering a broken call. */
        private const val RETRY_INTERVAL_MILLIS = 60_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, EnforcementService::class.java))
        }
    }
}
