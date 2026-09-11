pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "protego_parentis"

// Pure-JVM core. No Android dependencies, no device required to test.
include(":core-model")
include(":core-policy")
include(":core-provisioning")
include(":core-sync")

// Android library modules. Unlike the core-* modules above, these need
// Context, Keystore, or other framework APIs a pure-JVM module can't touch.
include(":security")
include(":usage")

// The Device Policy Controller that runs on the teen's phone.
// This is the module that produces the installable APK.
include(":app-child")
