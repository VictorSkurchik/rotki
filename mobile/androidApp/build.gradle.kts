plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(17)
}

android {
    namespace = "org.rotki.mobile.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.rotki.companion"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    flavorDimensions += "environment"
    productFlavors {
        create("dev") {
            dimension = "environment"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
        create("stage") {
            dimension = "environment"
            applicationIdSuffix = ".stage"
            versionNameSuffix = "-stage"
        }
        create("prod") {
            dimension = "environment"
        }
    }

    buildTypes {
        debug {
            versionNameSuffix = "-debug"
        }
        release {
            versionNameSuffix = "-release"
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
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

    sourceSets {
        getByName("androidTest").assets.directories.add("../protocol/v1")
    }
}

dependencies {
    implementation(project(":android:navigation"))
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.mlkit.barcode.scanning)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(platform(libs.koin.bom))
    testImplementation(libs.koin.android.test)
    testImplementation(libs.koin.test.junit4)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit)
}
