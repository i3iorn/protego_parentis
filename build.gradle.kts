// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kover) apply false
}

import org.gradle.api.tasks.testing.Test

/**
 * Unit tests must not run on an Oracle JDK: its crypto provider's own jar
 * self-test fails at class-init (`javax.crypto.JarVerifier`), breaking every
 * `Cipher.getInstance` — and with it all of the sync and vault tests. The
 * JetBrains Runtime (Android Studio's bundled JBR, or one unpacked under
 * `~/.jdks`) has no such regression, so when a JBR exists on this machine the
 * test JVMs run on it. Where none is installed — CI — tests keep the default
 * launcher.
 */
val jbrJava: String? = (
    // Android Studio's own JBR is a broken installation on this machine
    // (`jvm.cfg` fails to open), so only the unpacked runtimes count.
    (File(System.getProperty("user.home"), ".jdks")
        .listFiles { f -> f.isDirectory }
        ?.map { it.absolutePath }
        ?: emptyList())
    ).firstOrNull { candidate ->
        candidate.lowercase().contains("jbr") &&
            (File(candidate, "bin/java.exe").exists() || File(candidate, "bin/java").exists())
    }?.let { rootDir ->
        (sequenceOf("bin/java.exe", "bin/java").firstOrNull { File(rootDir, it).exists() })
            ?.let { rel -> File(rootDir, rel).absolutePath }
    }

if (jbrJava != null) {
    subprojects {
        tasks.withType<Test>().configureEach {
            executable = jbrJava
        }
    }
}
