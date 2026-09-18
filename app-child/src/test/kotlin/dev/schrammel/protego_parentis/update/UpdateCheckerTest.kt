package dev.schrammel.protego_parentis.update

import android.content.Context
import dev.schrammel.protego_parentis.sync.appVersionCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * [UpdateChecker.checkNow] used to `new` [UpdateClient], ask
 * [dev.schrammel.protego_parentis.enforcement.EnforcementCoordinator]
 * directly whether this is the device owner, and call [ApkInstaller] for
 * real — so the one security decision in this file, whether an update
 * installs completely unattended, could only ever be exercised on a real,
 * provisioned device. Every collaborator is now a parameter with a
 * production default, so this test drives all five outcomes without
 * touching the network or the platform installer.
 */
@RunWith(RobolectricTestRunner::class)
class UpdateCheckerTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun manifest(versionCode: Int, versionName: String = "9.9.9") = UpdateManifest(
        versionCode = versionCode,
        versionName = versionName,
        apkSha256 = "a".repeat(64),
        apkSize = 10L,
    )

    private class FakeUpdateSource(
        private val manifest: UpdateManifest?,
        private val downloadSucceeds: Boolean = true,
    ) : UpdateSource {
        var downloadCalledWith: UpdateManifest? = null
            private set

        override fun fetchManifest(): UpdateManifest? = manifest

        override fun downloadApk(manifest: UpdateManifest, destination: File): Boolean {
            downloadCalledWith = manifest
            if (downloadSucceeds) destination.writeBytes(ByteArray(0))
            return downloadSucceeds
        }
    }

    @Test
    fun `a feed that cannot be fetched fails the check`() {
        val outcome = UpdateChecker.checkNow(context, source = FakeUpdateSource(manifest = null))

        assertEquals(UpdateOutcome.CHECK_FAILED, outcome)
    }

    @Test
    fun `a manifest no newer than this build is up to date`() {
        val current = manifest(versionCode = appVersionCode(context))

        val outcome = UpdateChecker.checkNow(context, source = FakeUpdateSource(manifest = current))

        assertEquals(UpdateOutcome.UP_TO_DATE, outcome)
    }

    @Test
    fun `a newer manifest whose apk fails to download is reported as such`() {
        val newer = manifest(versionCode = appVersionCode(context) + 1)
        val source = FakeUpdateSource(manifest = newer, downloadSucceeds = false)

        val outcome = UpdateChecker.checkNow(context, source = source)

        assertEquals(UpdateOutcome.DOWNLOAD_FAILED, outcome)
        assertEquals(newer, source.downloadCalledWith)
    }

    @Test
    fun `a device owner installs the verified update without anyone's confirmation`() {
        val newer = manifest(versionCode = appVersionCode(context) + 1, versionName = "5.0.0")
        var installedVersion: String? = null

        val outcome = UpdateChecker.checkNow(
            context = context,
            source = FakeUpdateSource(manifest = newer),
            isDeviceOwner = { true },
            installSilently = { _, _, versionName -> installedVersion = versionName },
        )

        assertEquals(UpdateOutcome.INSTALLED_SILENTLY, outcome)
        assertEquals("5.0.0", installedVersion)
    }

    @Test
    fun `an unmanaged phone waits for confirmation instead of installing`() {
        val newer = manifest(versionCode = appVersionCode(context) + 1, versionName = "5.0.0")
        val store = PendingUpdateStore(context)
        var installCalled = false

        val outcome = UpdateChecker.checkNow(
            context = context,
            source = FakeUpdateSource(manifest = newer),
            isDeviceOwner = { false },
            installSilently = { _, _, _ -> installCalled = true },
            pendingUpdateStore = store,
        )

        assertEquals(UpdateOutcome.DOWNLOADED_PENDING_CONFIRMATION, outcome)
        assertEquals("5.0.0", store.pending?.versionName)
        assertFalse(installCalled)
    }
}
