import dev.detekt.gradle.Detekt
import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

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

    sourceSets {
        getByName("androidTest").assets.directories.add("../../protocol/v1")
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

tasks.withType<Detekt>().configureEach {
    val variantName =
        when (name) {
            "detektDebugUnitTest" -> "Debug"
            "detektReleaseUnitTest" -> "Release"
            else -> return@configureEach
        }
    val kotlinCompile = tasks.named<KotlinJvmCompile>("compile${variantName}UnitTestKotlin")
    val javaCompile = tasks.named<JavaCompile>("compile${variantName}UnitTestJavaWithJavac")
    dependsOn(javaCompile)
    classpath.setFrom(
        kotlinCompile.map { it.libraries },
        javaCompile.flatMap { it.destinationDirectory },
    )
}
