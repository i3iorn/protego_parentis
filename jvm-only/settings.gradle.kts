// The pure-JVM modules on their own, built without the Android Gradle Plugin.
//
// The main build resolves AGP and AndroidX from Google's Maven repository even
// to compile core-sync, so anywhere that repository is unreachable — a sandboxed
// agent session, a restricted network — nothing can be compiled or tested at
// all. These four modules need only Kotlin and JUnit from Maven Central, and
// hold the policy engine, the sync protocol and the wire formats, which is most
// of what is worth testing. Run from the repository root:
//
//     ./gradlew -p jvm-only test
//
// The modules are the real ones, by path; nothing here is a copy. See AGENTS.md.

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "protego_parentis-jvm"

listOf("core-model", "core-policy", "core-provisioning", "core-sync").forEach { module ->
    include(":$module")
    project(":$module").projectDir = file("../$module")
}
