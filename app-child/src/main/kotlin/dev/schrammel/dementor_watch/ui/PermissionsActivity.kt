package dev.schrammel.dementor_watch.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import dev.schrammel.dementor_watch.R
import dev.schrammel.dementor_watch.enforcement.EnforcementCoordinator
import dev.schrammel.dementor_watch.security.DeviceRole
import dev.schrammel.dementor_watch.security.RoleStore
import dev.schrammel.dementor_watch.usage.UsageAccess

private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

/**
 * What this app needs Android's permission for, and how to actually give it.
 *
 * This screen exists because "Open settings" was not enough. Three of the four
 * things here are not runtime permissions at all — there is no dialog to accept.
 * They live several levels down in Settings, under headings that differ by
 * manufacturer, and two of them are behind a screen Android deliberately makes
 * hard to find because granting them to the wrong app is genuinely dangerous.
 *
 * A parent who cannot find the switch concludes the app is broken. A parent who
 * finds it after ten minutes of hunting has learned that this app asks for things
 * it will not explain. Both are avoidable by writing down the steps.
 *
 * Two things this screen is careful about, because the subject is dangerous:
 *
 *  - **It states the risk of each one as plainly as the benefit.** Usage access
 *    means an app can see which apps you open. Anyone being walked through
 *    granting that should be told what they are granting, not reassured.
 *  - **It never claims a step is finished when it is not.** Every entry reads its
 *    real state, so a permission that looks granted is granted.
 *
 * Not PIN-gated. It reveals nothing and changes nothing; it is a page of
 * instructions, and the young person on the managed phone has as much right to
 * know what has been switched on as the parent who switched it.
 */
class PermissionsActivity : DementorActivity() {

    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        column = contentColumn()
        showColumn(column)
    }

    override fun onResume() {
        super.onResume()
        // Rebuilt every time, because the whole point of this screen is that the
        // user leaves it, changes something in Settings, and comes back. A stale
        // "not granted" after a successful trip is the one thing that would make
        // somebody grant it twice.
        render()
    }

    private fun render() {
        column.removeAllViews()
        column.addView(screenTitle(getString(R.string.perms_title)))
        column.addView(bodyText(getString(R.string.perms_intro)))

        entry(
            granted = UsageAccess.isGranted(this),
            title = R.string.perms_usage_title,
            why = R.string.perms_usage_why,
            risk = R.string.perms_usage_risk,
            steps = R.string.perms_usage_steps,
            action = R.string.perms_usage_action,
            intent = UsageAccess.settingsIntent(),
        )

        entry(
            granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED,
            title = R.string.perms_notifications_title,
            why = R.string.perms_notifications_why,
            risk = null,
            steps = R.string.perms_notifications_steps,
            action = R.string.perms_notifications_action,
            intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
        )

        entry(
            granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED,
            title = R.string.perms_location_title,
            why = R.string.perms_location_why,
            risk = R.string.perms_location_risk,
            steps = R.string.perms_location_steps,
            action = R.string.perms_location_action,
            intent = appSettings(),
        )

        entry(
            granted = isExemptFromBatterySaving(),
            title = R.string.perms_battery_title,
            why = R.string.perms_battery_why,
            risk = R.string.perms_battery_risk,
            steps = R.string.perms_battery_steps,
            action = R.string.perms_battery_action,
            // Deliberately the list, not the "please exempt me" dialog. Android
            // treats an app asking for that dialog directly as something to be
            // suspicious of, and Play restricts it — walking the user to the
            // setting is slower and stays inside what the platform intends.
            intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        )

        // Only on the phone that is meant to be enforcing anything. On a parent's
        // handset this is not an unfinished step, it is a thing that must never
        // happen — and a card offering it would be an invitation to wipe the
        // wrong phone.
        if (isManagedPhone()) {
            column.addView(sectionHeader(getString(R.string.perms_owner_header)))
            val card = card { }
            column.addView(card)
            // Off the main thread, like every other ownership check in this app.
            // Filled in when the answer arrives; until then the card is an empty
            // surface rather than a wrong answer that corrects itself.
            Background.load(this, { EnforcementCoordinator(this).isDeviceOwner() }) { owner ->
                describeOwnership(card, owner == true)
            }
        }

        column.addView(captionText(getString(R.string.perms_oem_note)))
    }

    /**
     * One permission: whether it is on, what it buys, what it costs, and the taps.
     *
     * [risk] is null only where there genuinely is none worth naming. Leaving it
     * out elsewhere would make this a page that talks somebody into granting
     * things, which given what two of these allow would be the wrong page to
     * write.
     */
    private fun entry(
        granted: Boolean,
        title: Int,
        why: Int,
        risk: Int?,
        steps: Int,
        action: Int,
        intent: Intent,
    ) {
        column.addView(sectionHeader(getString(title)))
        column.addView(
            card {
                addView(
                    TextView(this@PermissionsActivity).apply {
                        setText(if (granted) R.string.perms_state_on else R.string.perms_state_off)
                        styleAs(R.style.TextAppearance_Dementor_Subtitle)
                        setTextColor(
                            ContextCompat.getColor(
                                context,
                                if (granted) R.color.state_ok else R.color.state_warning,
                            ),
                        )
                    },
                )
                addView(captionText(getString(why)))
                risk?.let { addView(captionText(getString(it))) }

                if (!granted) {
                    addView(bodyText(getString(steps)))
                    addView(
                        actionButton().apply {
                            setText(action)
                            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                                topMargin = dimen(R.dimen.space_md)
                            }
                            // Not every phone has every one of these screens.
                            // Failing quietly here would leave a button that does
                            // nothing, which is worse than one that admits it.
                            setOnClickListener {
                                runCatching { startActivity(intent) }.onFailure {
                                    startActivity(appSettings())
                                }
                            }
                        },
                    )
                }
            },
        )
    }

    /**
     * Whether this phone is under control, and what to do about it if not.
     *
     * The factory-reset instructions appear **only when the phone is not yet
     * managed**. Once it is, this says so and stops — the steps for setting up
     * device ownership start with wiping the phone, and on a managed device that
     * is not a setup guide, it is the escape route, printed on a screen the person
     * being limited can open.
     */
    private fun describeOwnership(card: LinearLayout, owner: Boolean) {
        card.removeAllViews()
        card.addView(
            TextView(this).apply {
                setText(if (owner) R.string.perms_owner_done else R.string.perms_owner_missing)
                styleAs(R.style.TextAppearance_Dementor_Subtitle)
            },
        )
        card.addView(captionText(getString(R.string.perms_owner_why)))
        if (!owner) {
            card.addView(captionText(getString(R.string.perms_owner_steps)))
            card.addView(captionText(getString(R.string.perms_owner_warning)))
        }
    }

    private fun isManagedPhone(): Boolean =
        RoleStore(this).roleOrChild == DeviceRole.CHILD

    private fun isExemptFromBatterySaving(): Boolean =
        getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(packageName) == true

    private fun appSettings(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", packageName, null))

    companion object {
        fun intent(context: Context) = Intent(context, PermissionsActivity::class.java)
    }
}
