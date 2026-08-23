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
import dev.schrammel.dementor_watch.model.BudgetLevel
import dev.schrammel.dementor_watch.rules.GoalCoordinator
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        super.onDestroy()
    }

    /** Evaluates once and returns how long to wait before doing it again. */
    private fun evaluate(): Long {
        val now = Instant.now()
        val snapshot = coordinator.evaluate(now)
        if (snapshot == null) {
            updateNotification(getString(R.string.service_needs_permission))
            Diagnostics.record(this, DiagnosticEvent.USAGE_ACCESS_MISSING)
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

        // Piggybacks on the same tick rather than holding a connection open.
        // Failures are reported through the callback and never reach here.
        sync.pollNow()

        return delayUntil(snapshot.enforcement.nextChangeAt, now)
    }

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
