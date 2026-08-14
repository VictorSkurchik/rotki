import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

group = "org.rotki.mobile"
version = "1.0.0"

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val androidCompileSdk =
    libs
        .findVersion("android-compile-sdk")
        .get()
        .requiredVersion
        .toInt()
val androidMinSdk =
    libs
        .findVersion("android-min-sdk")
        .get()
        .requiredVersion
        .toInt()
val iosDeploymentTarget = libs.findVersion("ios-deployment-target").get().requiredVersion
val jvmToolchainVersion =
    libs
        .findVersion("jvm-toolchain")
        .get()
        .requiredVersion
        .toInt()
val configuredJvmTarget = JvmTarget.fromTarget(jvmToolchainVersion.toString())

kotlin {
    explicitApi()
    jvmToolchain(jvmToolchainVersion)

    jvm {
        compilerOptions {
            jvmTarget.set(configuredJvmTarget)
        }
    }

    android {
        compileSdk = androidCompileSdk
        minSdk = androidMinSdk
        compilerOptions {
            jvmTarget.set(configuredJvmTarget)
        }
        withHostTest { }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.configureEach {
            freeCompilerArgs += "-Xoverride-konan-properties=minVersion.ios=$iosDeploymentTarget"
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
