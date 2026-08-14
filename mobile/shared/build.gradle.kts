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
            export(project(":core:model"))
            export(project(":core:common"))
            export(project(":core:protocol"))
            export(project(":core:security-api"))
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:common"))
            api(project(":core:model"))
            api(project(":core:protocol"))
            api(project(":core:security-api"))
            api(libs.kotlinx.coroutines.core)
            implementation(project(":core:network"))
            implementation(libs.kotlinx.serialization.json)
            implementation(project(":feature:pairing:data"))
            implementation(project(":feature:pairing:domain"))
            implementation(project(":feature:pairing:presentation"))
        }
        commonTest.dependencies {
            implementation(project(":core:testing"))
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
