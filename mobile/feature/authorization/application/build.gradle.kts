plugins {
    id("rotki.kmp.library")
}

group = "org.rotki.mobile.feature.authorization"

kotlin {
    android {
        namespace = "org.rotki.mobile.feature.authorization.application"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":feature:authorization:domain"))
            implementation(project(":core:common"))
            implementation(project(":core:protocol"))
            implementation(project(":core:security-api"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
