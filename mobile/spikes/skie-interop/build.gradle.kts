import co.touchlab.skie.configuration.DefaultArgumentInterop

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.skie)
}

group = "org.rotki.mobile.spikes"
version = "0.1.0"

kotlin {
    explicitApi()
    jvmToolchain(17)

    jvm()
    listOf(
        macosArm64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "P03Interop"
            isStatic = false
            binaryOption("bundleId", "org.rotki.mobile.spikes.p03interop")
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

skie {
    isEnabled.set(
        providers.gradleProperty("rotki.skie.enabled")
            .map(String::toBoolean)
            .orElse(true),
    )
    analytics {
        enabled.set(false)
    }
    features {
        enableSwiftUIObservingPreview = false
        enableFutureCombineExtensionPreview = false
        enableFlowCombineConvertorPreview = false
        group {
            DefaultArgumentInterop.Enabled(false)
        }
    }
}

tasks.register("p03SkieFrameworkCheck") {
    group = "verification"
    description = "Builds representative Apple frameworks with the selected SKIE setting."
    dependsOn(
        "jvmTest",
        "linkDebugFrameworkMacosArm64",
        "linkDebugFrameworkIosArm64",
        "linkDebugFrameworkIosSimulatorArm64",
    )
}
