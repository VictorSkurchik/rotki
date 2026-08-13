plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.multiplatform.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

tasks.register("mobileCheck") {
    group = "verification"
    description = "Runs host-side shared and Android checks available on the current OS."
    dependsOn(
        ":androidApp:test",
        ":androidApp:assembleDebug",
        ":androidApp:lintDevDebug",
        ":shared:jvmTest",
        ":shared:testAndroidHostTest",
    )
}
