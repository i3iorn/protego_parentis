plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.schrammel.protego_parentis.context"

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
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

/**
 * Robolectric reaches into `jdk.internal.access` to build an Android
 * `FileDescriptor`. The JDK has not exported that package since 17, so without
 * these the whole Robolectric suite fails during setup with
 * `Failed to interact with raw FileDescriptor internals` before any test body
 * runs — which is exactly what happened on JDK 21. The opens are the set
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
    implementation(project(":core-model"))
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
