package dev.schrammel.protego_parentis.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.diagnostics.DiagnosticEvent
import dev.schrammel.protego_parentis.diagnostics.Diagnostics
import dev.schrammel.protego_parentis.ui.HomeActivity

/**
 * Keeps a device's realtime stream to the family topic alive while the app is
 * not in the foreground.
 *
 * Previously a parent learned about a child's request only by polling, fifteen
 * minutes apart at best. This service runs on **both** sides of a family and
 * holds the long-lived `/json` stream open, so a request, a chat message, a
 * decision or a lock command lands the moment it is published rather than on the
 * next poll. See [SyncSocketManager] for the stream itself; this service is just
 * the foreground shell Android requires for a persistent background connection,
 * with a permanently visible (but silent) notification so it is never hidden.
 *
 * The child device could host this stream inside [dev.schrammel.protego_parentis
 * .enforcement.EnforcementService]'s existing foreground service, but that
 * service is deliberately child-role-gated and owns screen-time enforcement;
 * keeping the connection in its own service lets either role host it without
 * entangling messaging with enforcing.
 */
class RealtimeSyncService : Service() {

    private var socket: SyncSocketManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        socket = SyncSocketManager(this)
        Diagnostics.record(this, DiagnosticEvent.SYNC_POLLED, mapOf("realtime" to 1L))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()

        if (intent?.action == ACTION_STOP) {
            socket?.close()
            stopSelf(startId)
            return START_NOT_STICKY
        }

        socket?.ensureConnected()

        // START_STICKY so the OS brings us back if the process is reclaimed and
        // the family needs reconnecting.
        return START_STICKY
    }

    override fun onDestroy() {
        socket?.shutdown()
        socket = null
        super.onDestroy()
    }

    private fun startAsForeground() {
        // FOREGROUND_SERVICE_TYPE_SPECIAL_USE only exists from API 34. On 33 the
        // two-argument call takes the type from the manifest, which is what we want
        // — passing the constant there would throw.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.realtime_channel_name),
            // LOW: permanently visible, but it must never buzz.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.realtime_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(getString(R.string.realtime_notification_title))
        .setContentText(getString(R.string.realtime_notification_text))
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
        private const val TAG = "DementorRealtime"
        private const val CHANNEL_ID = "realtime"
        private const val NOTIFICATION_ID = 2
        private const val ACTION_STOP = "dev.schrammel.protego_parentis.SYNC_STOP"

        /** Starts the realtime stream listener. Safe to call repeatedly. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, RealtimeSyncService::class.java))
        }

        /** Stops the realtime stream listener. */
        fun stop(context: Context) {
            runCatching {
                context.startForegroundService(
                    Intent(context, RealtimeSyncService::class.java).setAction(ACTION_STOP),
                )
            }
        }
    }
}
