plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

group = "org.rotki.mobile.spikes"
version = "0.1.0"

kotlin {
    explicitApi()
    jvmToolchain(17)

    jvm()
    iosArm64()
    iosSimulatorArm64()

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

tasks.register("p02Check") {
    group = "verification"
    description = "Runs the P0.2 JVM and iOS exact-decimal gates."
    dependsOn(
        "jvmTest",
        "iosSimulatorArm64Test",
        "linkDebugTestIosArm64",
    )
}
