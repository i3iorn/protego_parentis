package dev.schrammel.protego_parentis.enforcement

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import dev.schrammel.protego_parentis.AppRegistry
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.format.formatDuration
import dev.schrammel.protego_parentis.model.Reason
import java.time.Duration
import java.time.Instant
import kotlin.time.toKotlinDuration

/**
 * Tells the child, at the moment a restriction begins, what happened and why.
 *
 * Android's own "app paused" popup says only that an app is paused; this
 * notification carries the plain-words reason the dashboard's why-dialog
 * shows, and taps into that dialog for the full answer. The child who was
 * mid-app when the block hit lands on the explanation, not on a generic
 * screen.
 *
 * Posts on **transition into** a restricted state only — never on every tick.
 * The last posted state is remembered, exactly the way the warning ladder only
 * fires on a level change: a countdown that re-notifies every fifteen seconds
 * trains the reader to swipe without reading, which is worse than no
 * notification at all. A null signature (nothing restricted) is remembered
 * too, so leaving a restriction quietly and re-entering one announces the
 * re-entry.
 *
 * The channel and the gating are the warning ladder's, not a new one: a
 * restriction is the thing the ladder was counting down towards, so it rides
 * the same channel. The child can silence *this* announcement
 * ([NotificationKind.BLOCK_STARTED]) without silencing the ladder — the
 * restriction itself is not optional, only the knock on the door.
 */
class BlockNotifier(context: Context) {

    private val appContext = context.applicationContext
    private val notifications =
        checkNotNull(appContext.getSystemService(NotificationManager::class.java))
    private val prefs = appContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    init {
        // Same channel the warning ladder uses (its CHANNEL_ID is private to
        // it, so the value is repeated here): re-creating an existing channel
        // with identical settings is a no-op, and a second channel would ask
        // the child to manage two switches for one story.
        notifications.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.warning_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = appContext.getString(R.string.warning_channel_description)
            },
        )
    }

    /**
     * Compares this tick's restriction against the last one posted and
     * notifies only on a change into restriction.
     */
    fun onEvaluated(snapshot: EnforcementSnapshot) {
        val enforcement = snapshot.enforcement
        // Emergency is a parent's action with its own telling; announcing it
        // here too would post two notifications for one event.
        if (enforcement.emergency) return

        val headline = when {
            enforcement.deviceBudget.isExhausted -> BlockHeadline.Budget
            else -> enforcement.suspend.values
                .filterIsInstance<Reason.ContextRuleActive>()
                .filter { it.liftsAt != null }
                .sortedBy { it.liftsAt }
                .firstOrNull()
                ?.let { BlockHeadline.Rule(it.ruleId, it.ruleName, it.liftsAt) }
        } ?: run {
            remember(null)
            return
        }

        val signature = headline.signature
        if (signature == readSignature()) return
        remember(signature)

        // Silenced by choice rather than by not being built: the restriction
        // stands either way; only the announcement of it can be turned off.
        if (!NotificationPreferences(appContext).isEnabled(NotificationKind.BLOCK_STARTED)) return

        // The app the child was in when the block hit gets the deep link, but
        // only if this is the restriction actually stopping it — a rule about
        // games has nothing to say about the homework app on screen, and that
        // launch opens the status card instead.
        val foreground = snapshot.usage.foregroundPackage
        val blockedPkg = foreground?.takeIf { pkg ->
            when (headline) {
                // The device budget blocks everything; whichever app is on
                // screen is the one that just went dark.
                is BlockHeadline.Budget -> true
                is BlockHeadline.Rule ->
                    (enforcement.suspend[pkg] as? Reason.ContextRuleActive)?.ruleId == headline.ruleId
            }
        }

        val text = when (headline) {
            is BlockHeadline.Budget ->
                enforcement.nextChangeAt?.let { opensIn(it, snapshot.evaluatedAt) }
                    ?: appContext.getString(R.string.lock_status_rule_until_parent)
            is BlockHeadline.Rule -> headline.liftsAt?.let { opensIn(it, snapshot.evaluatedAt) }
                ?: appContext.getString(R.string.lock_status_rule_until_parent)
        }

        notifications.notify(
            NotificationIds.BLOCK_STARTED,
            NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(headline.title(appContext))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(
                    AppRegistry.notificationUx.openBlockedWhy(NotificationIds.BLOCK_STARTED, blockedPkg),
                )
                .build(),
        )
    }

    /** "Opens again in 2 h 3 m" — the same first half the dashboard shows. */
    private fun opensIn(at: Instant, now: Instant): String =
        appContext.getString(R.string.lock_status_opens_in, appContext.formatDuration(Duration.between(now, at).toKotlinDuration()))

    private fun readSignature(): String? = prefs.getString(KEY_SIGNATURE, null)

    private fun remember(signature: String?) {
        prefs.edit { putString(KEY_SIGNATURE, signature) }
    }

    private sealed interface BlockHeadline {
        val signature: String

        /** The day's allowance is spent — the device-wide answer. */
        data object Budget : BlockHeadline {
            override val signature get() = "budget"
        }

        /** A contextual rule (including bedtime) now applies. */
        data class Rule(
            val ruleId: String,
            val ruleName: String,
            val liftsAt: Instant?,
        ) : BlockHeadline {
            override val signature get() = "rule:$ruleId"
        }

        fun title(context: Context): String = when (this) {
            is Budget -> context.getString(R.string.lock_status_budget)
            is Rule -> context.getString(R.string.lock_status_rule, ruleName)
        }
    }

    private companion object {
        const val FILE_NAME = "blocks"
        const val KEY_SIGNATURE = "signature"
        const val CHANNEL_ID = "warnings"
    }
}
