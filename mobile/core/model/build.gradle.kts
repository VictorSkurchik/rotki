plugins {
    id("rotki.kmp.library")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "org.rotki.mobile.core.model"
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.core)
            implementation(libs.ionspin.bignum)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
