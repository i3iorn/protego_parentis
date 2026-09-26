import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kover)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        allWarningsAsErrors.set(true)
        // Limits the *API surface*, which jvmTarget does not. jvmTarget picks the
        // bytecode version; without this, code compiled on a newer JDK can call a
        // method that JDK added, produce a class file stamped 11, and throw
        // NoSuchMethodError on a handset. No unit test would catch it — they run on
        // the build JDK, not on Android. The daemon JVM is whatever the contributor
        // has, so this is the only thing keeping the two honest.
        freeCompilerArgs.add("-Xjdk-release=11")
    }
}

dependencies {
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
}
