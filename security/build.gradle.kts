plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.schrammel.protego_parentis.security"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 33
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests {
            // Some of this module's classes take a Context (RoleStore, PinLock,
            // DeviceRole, ...); Robolectric needs the merged manifest and resource
            // table to stand one up, same as app-child.
            isIncludeAndroidResources = true
        }
    }
}

// Same as app-child and the core-* modules: a warning here is otherwise the
// kind of thing nobody sees until it is a bug.
kotlin {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

/**
 * Robolectric reaches into `jdk.internal.access` to build an Android
 * `FileDescriptor`. The JDK has not exported that package since 17, so without
 * these the whole Robolectric suite fails during setup with an
 * `IllegalAccessException` before any test body runs. The opens are the set
 * Robolectric's own documentation lists for a modern JDK.
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
    // For DeviceId, Fingerprints, Signer — KeystoreSigner is the one class here
    // that talks to core-sync's wire types directly.
    implementation(project(":core-sync"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.biometric)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
