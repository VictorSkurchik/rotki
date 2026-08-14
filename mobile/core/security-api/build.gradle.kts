plugins {
    id("rotki.kmp.library")
}

kotlin {
    android {
        namespace = "org.rotki.mobile.core.security.api"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:protocol"))
        }
    }
}
