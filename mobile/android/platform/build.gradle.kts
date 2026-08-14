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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        managedDevices {
            localDevices {
                create("pixel2Api28") {
                    device = "Pixel 2"
                    apiLevel = 28
                    systemImageSource = "google"
                    require64Bit = true
                }
                create("pixel8Api36") {
                    device = "Pixel 8"
                    apiLevel = 36
                    systemImageSource = "google"
                    require64Bit = true
                }
            }
        }
    }
}

dependencies {
    api(project(":core:common"))
    api(project(":core:security-api"))
    implementation(project(":core:protocol"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit)
}
