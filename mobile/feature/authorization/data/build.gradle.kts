plugins {
    id("rotki.kmp.library")
    alias(libs.plugins.kotlin.serialization)
}

group = "org.rotki.mobile.feature.authorization"

kotlin {
    android {
        namespace = "org.rotki.mobile.feature.authorization.data"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":feature:authorization:domain"))
            implementation(project(":core:network"))
            implementation(project(":core:protocol"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(project(":core:testing"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}
