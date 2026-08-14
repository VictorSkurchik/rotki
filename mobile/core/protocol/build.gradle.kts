plugins {
    id("rotki.kmp.library")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "org.rotki.mobile.core.protocol"
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
            implementation(libs.ionspin.bignum)
        }
    }
}
