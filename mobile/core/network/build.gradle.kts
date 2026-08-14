plugins {
    id("rotki.kmp.library")
}

kotlin {
    android {
        namespace = "org.rotki.mobile.core.network"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:protocol"))
            api(libs.kotlinx.serialization.core)
            api(libs.ktor.client.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.websockets)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}
