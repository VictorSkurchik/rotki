plugins {
    id("rotki.kmp.library")
}

group = "org.rotki.mobile.feature.authorization"

kotlin {
    android {
        namespace = "org.rotki.mobile.feature.authorization.domain"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:protocol"))
        }
    }
}
