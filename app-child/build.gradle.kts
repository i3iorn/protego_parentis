plugins {
    alias(libs.plugins.android.application)
}

/**
 * Reads keystore.properties the way a person writes it, not the way Java would.
 *
 * Deliberately not `java.util.Properties`. That format treats a backslash as an
 * escape character, so the perfectly reasonable Windows path
 *
 *     storeFile=C:\Users\me\vault\upload.jks
 *
 * loads as `C:Usersmevaultupload.jks` — every separator silently swallowed. It
 * does not strip surrounding quotes either, so wrapping the path in them, which is
 * the natural thing to do when it might contain a space, puts the quote characters
 * inside the filename. Both mistakes produce the same thing: a mangled relative
 * path, and a "keystore not found" naming a file nobody typed. That cost a release
 * build once already; it should not be able to cost another.
 *
 * So: split each line on the first `=`, trim, drop one layer of matching quotes,
 * and take the rest verbatim. Passwords with `=` or `\` in them survive too, which
 * `Properties` would also have quietly rewritten.
 */
fun readSigningProperties(): Map<String, String> {
    val file = rootProject.file("keystore.properties")
    if (!file.exists()) return emptyMap()

    return file.readLines()
        .map(String::trim)
        .filterNot { it.isEmpty() || it.startsWith("#") || it.startsWith("!") }
        .mapNotNull { line ->
            val separator = line.indexOf('=').takeIf { it > 0 } ?: return@mapNotNull null
            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim().removeSurroundingQuotes()
            key to value
        }
        .toMap()
}

fun String.removeSurroundingQuotes(): String = when {
    length >= 2 && startsWith('"') && endsWith('"') -> substring(1, length - 1)
    length >= 2 && startsWith('\'') && endsWith('\'') -> substring(1, length - 1)
    else -> this
}

/**
 * Where the release signing material is described.
 *
 * The file is listed in .gitignore and is not in this repository, so a checkout
 * builds debug happily and simply cannot sign a release. That is the intended
 * failure: a release keystore cannot be revoked or reissued, and losing control of
 * one means anybody can ship an update that Play and every installed device will
 * accept as ours. See docs/RELEASING.md for how to create it.
 */
val signingProperties = readSigningProperties()

val keystorePath = signingProperties["storeFile"]?.takeIf { it.isNotBlank() }

/**
 * Resolved against the repository root, so a relative path in the file works and
 * an absolute one is left alone.
 */
val keystoreFile = keystorePath?.let(rootProject::file)

val canSignRelease = keystoreFile != null

android {
    namespace = "dev.schrammel.protego_parentis"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "dev.schrammel.protego_parentis"
        minSdk = 33
        targetSdk = 37
        versionCode = 55
        versionName = "1.5.0"
    }

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = keystoreFile
                storePassword = signingProperties["storePassword"]
                keyAlias = signingProperties["keyAlias"]
                keyPassword = signingProperties["keyPassword"]

                // V1 off. minSdk is 33, so every device that can install this
                // verifies with v2 or better, and a v1 signature is only a second
                // way in for anyone who finds a flaw in the older scheme.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // Shrinks and obfuscates. Worth saying plainly: this is the one build
            // difference that can break something which worked in debug, because
            // anything the platform reaches by name is invisible to R8. The rules
            // file lists every such case and why. A release build has to be run on
            // a device before it goes anywhere near a review queue.
            optimization {
                enable = true
            }
            // Unreferenced resources go too. This app builds most of its screens
            // in code, so R8 sees every string and drawable through R fields it
            // can follow — but AppCompat brings a great deal that nothing here
            // ever draws.
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )

            // Null when there is no keystore.properties, which leaves the artifact
            // unsigned rather than silently signing it with the debug key. An
            // unsigned bundle is rejected by Play; a debug-signed one is rejected
            // too, but only after looking like it worked.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    androidResources {
        // Both languages ship, and nothing else does. Without this the bundle also
        // carries every locale AppCompat brings with it — dozens of translations of
        // "OK" for an app whose own screens exist in two languages.
        localeFilters += setOf("en", "sv")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests {
            // Robolectric needs the merged manifest and the resource table to
            // stand up a real framework; without this it gets neither and every
            // test that touches a Context fails at startup rather than in a way
            // that says what is missing.
            isIncludeAndroidResources = true
        }
    }
}

/**
 * Warnings are errors here, as they already are in the four core modules.
 *
 * This module was the exception, which was backwards: it is the largest by a
 * wide margin and the least covered by tests, so it is where a warning is most
 * likely to be the only notice anybody gets. It was left out until now only
 * because nothing compiled it — there was no CI, and a warning nobody sees
 * cannot be made into an error.
 *
 * The two it started with are fixed in this commit. One of them was a real
 * dated breakage: a return from an expression-bodied function, which Kotlin
 * says stops compiling in language version 2.5.
 *
 * A top-level block rather than `android.kotlinOptions`, which AGP 9 removed
 * along with the need to apply the Kotlin plugin at all — the Android plugin
 * now registers the Kotlin compilations itself.
 */
kotlin {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

/**
 * Robolectric reaches into `jdk.internal.access` to build an Android
 * `FileDescriptor`. The JDK has not exported that package since 17, so without
 * these the whole Robolectric suite fails during setup with an
 * `IllegalAccessException` before any test body runs — which is exactly what
 * happened on JDK 21. The opens are the set Robolectric's own documentation
 * lists for a modern JDK.
 */
tasks.withType<Test>().configureEach {
    jvmArgs(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
    )
}

dependencies {
    implementation(project(":core-policy"))
    implementation(project(":core-provisioning"))
    implementation(project(":core-sync"))
    implementation(project(":security"))
    implementation(project(":usage"))
    implementation(project(":context"))
    implementation(project(":diagnostics"))
    implementation(project(":format"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.work)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.work.testing)
}

/**
 * Refuses to build a release nobody can publish.
 *
 * Without this the missing-keystore case produces an unsigned bundle and a green
 * build, and the problem surfaces much later as an upload rejection carrying a far
 * less helpful message than this one.
 *
 * Checked while configuring rather than from a `doFirst`. A task action written in
 * this script captures the script object along with it, which the configuration
 * cache cannot serialise — so the tidier-looking version of this fails every
 * release build for a completely unrelated reason.
 */
val releaseRequested = gradle.startParameter.taskNames.any {
    it.substringAfterLast(':') in setOf("bundleRelease", "assembleRelease")
}

if (releaseRequested) {
    check(canSignRelease) {
        "No keystore.properties at the repository root, so this build cannot be " +
            "signed and Play will not accept it. See docs/RELEASING.md."
    }
    // Checked here rather than left to the signing task, which reports the path
    // it was given without saying where that path came from — and when the path
    // has been mangled, the filename in that message is one nobody ever typed.
    check(keystoreFile?.exists() == true) {
        "keystore.properties points at a keystore that is not there:\n" +
            "  configured: $keystorePath\n" +
            "  resolved:   ${keystoreFile?.absolutePath}\n" +
            "Write the path with forward slashes and no quotes, for example\n" +
            "  storeFile=C:/Users/you/vault/upload.jks"
    }
}

/**
 * Prints `versionCode` and `versionName` as `KEY=value` lines, for the release
 * workflow to read when it builds `update.json` (see docs/RELEASING.md) —
 * grepping this file's own Kotlin for two `val` assignments is exactly the
 * kind of thing that silently breaks the moment somebody reformats it. This
 * reads the values Gradle already parsed instead.
 */
tasks.register("printVersionInfo") {
    doLast {
        println("versionCode=${android.defaultConfig.versionCode}")
        println("versionName=${android.defaultConfig.versionName}")
    }
}
