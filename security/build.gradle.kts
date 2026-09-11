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

dependencies {
    // For DeviceId, Fingerprints, Signer — KeystoreSigner is the one class here
    // that talks to core-sync's wire types directly.
    implementation(project(":core-sync"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.biometric)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
