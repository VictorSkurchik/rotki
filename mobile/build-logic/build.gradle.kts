import dev.detekt.gradle.Detekt
import dev.detekt.gradle.extensions.FailOnSeverity
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType
import org.jlleitschuh.gradle.ktlint.tasks.BaseKtLintCheckTask

plugins {
    `kotlin-dsl`
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

group = "org.rotki.mobile.buildlogic"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

dependencies {
    implementation(libs.android.gradle.plugin)
    implementation(libs.kotlin.gradle.plugin)
}

ktlint {
    version.set(libs.versions.ktlint.engine)
    android.set(false)
    ignoreFailures.set(false)
    outputToConsole.set(true)
    reporters {
        reporter(ReporterType.PLAIN)
        reporter(ReporterType.CHECKSTYLE)
        reporter(ReporterType.SARIF)
    }
}

val handwrittenBuildLogicKotlin = fileTree("src/main/kotlin")

listOf(
    "runKtlintCheckOverMainSourceSet",
    "runKtlintFormatOverMainSourceSet",
).forEach { taskName ->
    tasks.named<BaseKtLintCheckTask>(taskName) {
        setSource(handwrittenBuildLogicKotlin)
    }
}

detekt {
    source.setFrom(
        files(
            "src/main/kotlin",
            "build.gradle.kts",
            "settings.gradle.kts",
        ),
    )
    config.setFrom(file("../config/quality/detekt.yml"))
    buildUponDefaultConfig = true
    allRules = false
    ignoreFailures = false
    failOnSeverity = FailOnSeverity.Info
    basePath.set(projectDir.parentFile)
}

tasks.withType<Detekt>().configureEach {
    reports {
        checkstyle.required.set(true)
        html.required.set(true)
        markdown.required.set(true)
        sarif.required.set(true)
    }
}

val qualityCheck =
    tasks.register("qualityCheck") {
        group = "verification"
        description = "Runs non-mutating checks for the mobile convention plugins."
        dependsOn(tasks.named("ktlintCheck"), tasks.named("detekt"))
    }

tasks.register("qualityFormat") {
    group = "formatting"
    description = "Formats the mobile convention-plugin sources."
    dependsOn(tasks.named("ktlintFormat"))
}

tasks.named("check") {
    dependsOn(qualityCheck)
}
