package dev.schrammel.protego_parentis.usage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.core.net.toUri
import androidx.core.content.ContextCompat
import dev.schrammel.protego_parentis.model.PackageName

data class AppInfo(
    val pkg: PackageName,
    val label: String,
    val icon: Drawable?,
)

/**
 * The apps a person can actually open.
 *
 * Launchable apps only. The full package list includes hundreds of system
 * components that no one recognises, which would make both the parent's app list
 * and the teen's dashboard useless.
 *
 * Results are cached process-wide. Querying the package manager and loading a
 * label and icon for every launchable app is one of the slowest things this app
 * does, and several screens were doing it on the main thread on every render.
 * The cache is invalidated when apps are installed or removed, which is the only
 * time the answer changes.
 *
 * Every method here may block; call them through
 * [dev.schrammel.protego_parentis.ui.Background].
 */
class InstalledApps(context: Context) {

    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager

    fun launchable(): List<AppInfo> {
        cached?.let { return it }

        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager
            .queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
            .map { resolved ->
                AppInfo(
                    pkg = PackageName(resolved.activityInfo.packageName),
                    label = runCatching { resolved.loadLabel(packageManager).toString() }
                        .getOrDefault(resolved.activityInfo.packageName),
                    icon = runCatching { resolved.loadIcon(packageManager) }.getOrNull(),
                )
            }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }

        ensureWatching(appContext)
        cached = apps
        return apps
    }

    /**
     * The apps that can open a web page (WF-2).
     *
     * Asked of the package manager rather than matched against a list of known
     * browser names: a family only needs one obscure browser for a hard-coded
     * list to be wrong, and being wrong here means an unfiltered way onto the web
     * that looks filtered.
     *
     * Cached with the app list, because it changes exactly when that does.
     */
    fun browsers(): Set<PackageName> {
        cachedBrowsers?.let { return it }

        val probe = Intent(Intent.ACTION_VIEW, "https://example.com".toUri())
        val found = packageManager
            .queryIntentActivities(probe, PackageManager.ResolveInfoFlags.of(0L))
            .mapTo(mutableSetOf()) { PackageName(it.activityInfo.packageName) }

        ensureWatching(appContext)
        cachedBrowsers = found
        return found
    }

    /** Labels are looked up far more often than the list changes, so they cache too. */
    fun labelFor(pkg: PackageName): String {
        labels[pkg]?.let { return it }

        val label = runCatching {
            packageManager
                .getApplicationInfo(pkg.value, PackageManager.ApplicationInfoFlags.of(0L))
                .loadLabel(packageManager)
                .toString()
        }.getOrDefault(pkg.value)

        labels[pkg] = label
        return label
    }

    companion object {
        @Volatile
        private var cached: List<AppInfo>? = null

        @Volatile
        private var cachedBrowsers: Set<PackageName>? = null

        private val labels = java.util.concurrent.ConcurrentHashMap<PackageName, String>()

        @Volatile
        private var watching = false

        fun invalidate() {
            cached = null
            cachedBrowsers = null
            labels.clear()
        }

        /**
         * Drops the cache when the set of installed apps changes.
         *
         * Registered once, lazily, against the application context — a stale list
         * would mean a newly installed app never appearing in the picker, which
         * looks exactly like the feature being broken.
         */
        @Synchronized
        private fun ensureWatching(context: Context) {
            if (watching) return
            watching = true

            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
            // Explicitly not exported. These are protected system broadcasts and so
            // are exempt from the rule that bites from API 34, but stating it is
            // free and removes the need to be right about the exemption.
            ContextCompat.registerReceiver(
                context,
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) = invalidate()
                },
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }
}
