plugins {
    id("rotki.kmp.library")
}

kotlin {
    android {
        namespace = "org.rotki.mobile.core.testing"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:protocol"))
            api(libs.kotlinx.serialization.json)
        }
    }
}
