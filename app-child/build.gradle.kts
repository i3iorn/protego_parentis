plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.schrammel.dementor_watch"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "dev.schrammel.dementor_watch"
        minSdk = 33
        targetSdk = 37
        versionCode = 23
        versionName = "0.23-test"

    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":core-policy"))
    implementation(project(":core-provisioning"))
    implementation(project(":core-sync"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.work)
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
