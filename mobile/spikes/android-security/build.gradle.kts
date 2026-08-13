buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP 9 supplies built-in Kotlin. Adding the newer KGP to the buildscript is the
        // supported way to override AGP's bundled compiler without applying kotlin-android.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
}

tasks.register("p03AndroidBuild") {
    group = "verification"
    description = "Builds and runs all host-side checks for the P0.3 Android security spike."
    dependsOn(
        ":app:assembleDebug",
        ":app:assembleDebugAndroidTest",
        ":app:lintDebug",
        ":app:testDebugUnitTest",
    )
}

tasks.register("p03AndroidApi28Check") {
    group = "verification"
    description = "Runs non-interactive instrumentation tests on a managed API 28 device."
    dependsOn(":app:pixel2Api28DebugAndroidTest")
}
