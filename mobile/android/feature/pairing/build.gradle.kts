import dev.detekt.gradle.Detekt
import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    explicitApi()
    jvmToolchain(17)
}

android {
    namespace = "org.rotki.mobile.android.feature.pairing"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.runtime)
    api(libs.androidx.compose.ui)

    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.mlkit.barcode.scanning)

    testImplementation(libs.junit)
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
