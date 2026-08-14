plugins {
    alias(libs.plugins.android.library)
}

kotlin {
    explicitApi()
    jvmToolchain(17)
}

android {
    namespace = "org.rotki.mobile.android.platform"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core:common"))

    testImplementation(libs.junit)
}
