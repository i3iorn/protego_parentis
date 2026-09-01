import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
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
val signingProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}

val canSignRelease = signingProperties.getProperty("storeFile") != null

android {
    namespace = "dev.schrammel.dementor_watch"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "dev.schrammel.dementor_watch"
        minSdk = 33
        targetSdk = 37
        versionCode = 37
        versionName = "1.1.1"
    }

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = rootProject.file(signingProperties.getProperty("storeFile"))
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")

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

dependencies {
    implementation(project(":core-policy"))
    implementation(project(":core-provisioning"))
    implementation(project(":core-sync"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
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

if (releaseRequested && !canSignRelease) {
    throw GradleException(
        "No keystore.properties at the repository root, so this build cannot be " +
            "signed and Play will not accept it. See docs/RELEASING.md.",
    )
}
