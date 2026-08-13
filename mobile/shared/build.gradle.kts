import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
}

group = "org.rotki.mobile"
version = "1.0.0"

kotlin {
    explicitApi()
    jvmToolchain(17)

    jvm()

    android {
        namespace = "org.rotki.mobile.shared"
        compileSdk = 37
        minSdk = 28
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        withHostTest { }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.configureEach {
            freeCompilerArgs += "-Xoverride-konan-properties=minVersion.ios=17.0"
        }
        target.binaries.framework {
            baseName = "RotkiShared"
            binaryOption("bundleId", "com.rotki.companion.shared")
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.ionspin.bignum)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
