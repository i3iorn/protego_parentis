package dev.schrammel.protego_parentis.enforcement

import android.app.NotificationChannel
import android.app.NotificationManager
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
import dev.schrammel.protego_parentis.AppRegistry
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.context.PlaceWatcher
import dev.schrammel.protego_parentis.diagnostics.CrashCapture
import dev.schrammel.protego_parentis.diagnostics.DiagnosticEvent
import dev.schrammel.protego_parentis.diagnostics.Diagnostics
import dev.schrammel.protego_parentis.rules.AppReviewStore
import dev.schrammel.protego_parentis.rules.GoalCoordinator
import dev.schrammel.protego_parentis.rules.SosStore
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.RoleStore
import dev.schrammel.protego_parentis.usage.InstalledApps
import java.time.Instant
import java.util.concurrent.Executors

/**
 * Keeps enforcement alive and re-evaluates on a tick.
 *
 * Runs in the foreground with a permanent notification, which is required twice
 * over: Android's background limits would otherwise kill it, and Play's policy
 * requires a monitored device to visibly show that it is monitored. The
 * notification is not dismissible, and that is deliberate.
 *
 * The re-evaluation interval is driven by the engine's own `nextChangeAt` rather
 * than a fixed poll — see [TickWork.nextDelay].
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

    /**
     * Publishes SOS positions off the tick thread — see [repeatEmergency].
     * Single-threaded so fixes are sent in the order they were asked for.
     */
    private val locator = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dementor-locate").apply { isDaemon = true }
    }
    private lateinit var coordinator: EnforcementCoordinator
    private lateinit var warnings: WarningNotifier
    private lateinit var goals: GoalCoordinator
    private lateinit var goalNotifier: GoalNotifier
    private lateinit var review: AppReviewStore
    private lateinit var installedApps: InstalledApps
    private lateinit var requestNotifier: RequestNotifier
    private lateinit var sos: SosStore
    private lateinit var places: PlaceWatcher

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
            handler.post {
                // Only the broadcast needs the cache dropped: it is the one path
                // woken by an install this process has not seen. The tick's own
                // sweep must not invalidate — paying a cold PackageManager walk
                // every evaluation is exactly what the cache exists to avoid,
                // and the cache's own PACKAGE_ADDED/REMOVED receiver already
                // keeps it fresh between sweeps.
                InstalledApps.invalidate()
                runCatching { tickWork.sweepForNewApps() }
                    .onFailure { Log.w(TAG, "Could not check for new apps", it) }
            }
        }
    }

    /**
     * Fires as the phone shuts down (LOC-4).
     *
     * Best effort, and unreliable by nature: a shutting-down phone has seconds and
     * may lose the network first. Its absence therefore means nothing — but when
     * it does arrive it lets a parent see "switched off" rather than "gone quiet",
     * which is the difference between shrugging and worrying.
     */
    private val onShutdown = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SHUTDOWN) return
            runCatching { AppRegistry.realtimeSync.publishStopping() }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            // An exception escaping here would kill the Runnable and stop
            // enforcement permanently, with the notification still claiming the
            // rules are active. Always reschedule, whatever happened.
            val nextIn = try {
                tickWork.run(Instant.now())
            } catch (e: Exception) {
                Log.e(TAG, "Evaluation failed; retrying", e)
                Diagnostics.record(this@EnforcementService, DiagnosticEvent.EVALUATION_FAILED)
                // Log.e only reaches a computer plugged in with USB debugging
                // on, which a device owner can switch off — see CrashCapture's
                // own doc comment. This is the only other place the actual
                // exception survives.
                CrashCapture.recordEvaluationFailure(this@EnforcementService, e)
                RETRY_INTERVAL_MILLIS
            }
            handler.postDelayed(this, nextIn)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Builds the collaborator that owns "read the rules, measure usage, ask
     * the engine, apply the answer" — see [EnforcementCoordinator]'s own doc
     * comment for why its constructor now takes each of *its* collaborators
     * as parameters with production defaults. A `Service` cannot itself be
     * constructor-injected (Android instantiates it with a no-arg
     * constructor, the same reason [dev.schrammel.protego_parentis.diagnostics.CrashRecovery.install]
     * takes a supplier rather than a plain value), so this is the seam
     * instead: a settable factory a test can replace, before [onCreate]
     * runs, with one built against a fake
     * [dev.schrammel.protego_parentis.policy.reconcile.DevicePolicyPort],
     * without needing a real device to reach that branch.
     */
    internal var coordinatorFactory: (Context) -> EnforcementCoordinator = { EnforcementCoordinator(it) }

    /**
     * The tick's behaviours, built once here against the same collaborators the
     * receivers use — see [TickWork] for what moved and why.
     */
    private lateinit var tickWork: TickWork

    override fun onCreate() {
        super.onCreate()
        coordinator = coordinatorFactory(this)
        review = AppRegistry.childStores.appReview
        installedApps = InstalledApps(this)
        requestNotifier = RequestNotifier(this)
        warnings = WarningNotifier(this)
        goals = GoalCoordinator(this)
        goalNotifier = GoalNotifier(this)
        // The managed phone streams too, so a parent's decision, a bonus, a daily
        // limit change or a lock command lands as soon as it is published rather
        // than on the next enforcement tick. Idempotent if already running.
        AppRegistry.realtimeSync.connectRealtime()
        sos = AppRegistry.childStores.sos
        places = PlaceWatcher(this)
        tickWork = TickWork(
            context = this,
            coordinator = coordinator,
            sync = AppRegistry.realtimeSync,
            warnings = warnings,
            review = review,
            installedApps = installedApps,
            requestNotifier = requestNotifier,
            sos = sos,
            places = places,
            locator = locator,
            onNotification = ::updateNotification,
        )
        createNotificationChannel()
        ContextCompat.registerReceiver(
            this,
            onUnlocked,
            IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            this,
            onShutdown,
            IntentFilter(Intent.ACTION_SHUTDOWN),
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
        if (intent?.action == ACTION_LOCK_NOW) lockScreen()

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
        locator.shutdownNow()
        runCatching { unregisterReceiver(onUnlocked) }
        runCatching { unregisterReceiver(onPackagesChanged) }
        runCatching { unregisterReceiver(onShutdown) }
        super.onDestroy()
    }

    /**
     * Locks the screen, on a parent's instruction (the "lock now" button).
     *
     * Through [DeviceLocker] rather than the reconciler: a lock is an edge, not a
     * level, and the reconciler is a desired-state differ that would either
     * re-lock the phone on every tick or need a latch it has no business holding.
     */
    private fun lockScreen() {
        handler.post {
            val result = coordinator.lockDevice()
            if (result.hadNoLockScreen) {
                // The screen went dark and swiped straight back open. Worth
                // saying, because a parent has just been told it locked.
                Log.w(TAG, "Locked a phone with no lock screen set")
            }
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
        .setContentIntent(AppRegistry.notificationUx.openHome(NOTIFICATION_ID))
        .build()

    companion object {
        private const val TAG = "DementorService"
        private const val CHANNEL_ID = "enforcement"
        private const val NOTIFICATION_ID = 1

        /**
         * Never sleep longer than this, so drift and missed events self-correct.
         *
         * Five minutes, not one: idling at the maximum interval rather than
         * burning battery on pointless evaluations was always the promise; at
         * 60 s it was worth little — a full evaluation pipeline (usage replay,
         * policy read, heartbeat, poll) every minute, day and night. Anything
         * that genuinely arrives as an event (a lock command, a rule push, an
         * install) rides the realtime stream or a broadcast and does not wait
         * for the tick. Two things do wait for it, and [TickWork.nextDelay]
         * keeps the tick short for them: an active emergency's repeat, and
         * rules reading Wi-Fi, a place, a nearby device or battery — nothing
         * broadcasts those changes.
         */
        internal const val MAX_INTERVAL_MILLIS = 5 * 60_000L

        /** Backs off briefly after a failure rather than hammering a broken call. */
        private const val RETRY_INTERVAL_MILLIS = 30_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, EnforcementService::class.java))
        }

        /** Locks the screen now, on a parent's instruction. */
        fun lockNow(context: Context) {
            context.startForegroundService(
                Intent(context, EnforcementService::class.java).setAction(ACTION_LOCK_NOW),
            )
        }

        private const val ACTION_LOCK_NOW = "dev.schrammel.protego_parentis.LOCK_NOW"
    }
}
