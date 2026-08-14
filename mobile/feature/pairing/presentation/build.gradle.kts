plugins {
    id("rotki.kmp.library")
}

kotlin {
    android {
        namespace = "org.rotki.mobile.feature.pairing.presentation"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":feature:pairing:domain"))
        }
    }
}
