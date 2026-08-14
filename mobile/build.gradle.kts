import dev.detekt.gradle.Detekt
import dev.detekt.gradle.extensions.DetektExtension
import dev.detekt.gradle.extensions.FailOnSeverity
import org.gradle.api.artifacts.ProjectDependency
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.multiplatform.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktlint)
}

val exactGeneratedKotlinPathsByProject =
    mapOf(
        ":core:model" to
            setOf(
                "**/GeneratedExactDecimalVectors.kt",
            ),
        ":core:protocol" to
            setOf(
                "**/GeneratedProtocolVocabulary.kt",
            ),
        ":shared" to
            setOf(
                "**/GeneratedProtocolFixtures.kt",
            ),
    )
val ktlintEngineVersion =
    libs.versions.ktlint.engine
        .get()

data class ModuleBoundaryRule(
    val allowedProjectDependencies: Set<String>,
    val forbiddenGroupPrefixes: Set<String> = emptySet(),
    val forbiddenModules: Set<Pair<String, String>> = emptySet(),
    val forbiddenPluginIds: Set<String> = emptySet(),
)

val nativeUiDependencyGroupPrefixes =
    setOf(
        "androidx.",
        "io.insert-koin",
        "org.jetbrains.compose",
    )
val featureInfrastructureGroupPrefixes = nativeUiDependencyGroupPrefixes + "io.ktor"
val forbiddenNonUiKmpPluginIds =
    setOf(
        "com.android.application",
        "com.android.library",
        "org.jetbrains.compose",
        "org.jetbrains.kotlin.android",
        "org.jetbrains.kotlin.plugin.compose",
    )
val featureModuleBoundaryRules =
    mapOf(
        ":androidApp" to
            ModuleBoundaryRule(
                allowedProjectDependencies = setOf(":shared"),
            ),
        ":core:model" to
            ModuleBoundaryRule(
                allowedProjectDependencies = emptySet(),
                forbiddenGroupPrefixes = featureInfrastructureGroupPrefixes,
                forbiddenPluginIds = forbiddenNonUiKmpPluginIds,
            ),
        ":core:protocol" to
            ModuleBoundaryRule(
                allowedProjectDependencies = emptySet(),
                forbiddenGroupPrefixes = featureInfrastructureGroupPrefixes,
                forbiddenPluginIds = forbiddenNonUiKmpPluginIds,
            ),
        ":core:network" to
            ModuleBoundaryRule(
                allowedProjectDependencies = setOf(":core:protocol"),
                forbiddenGroupPrefixes = nativeUiDependencyGroupPrefixes,
                forbiddenPluginIds = forbiddenNonUiKmpPluginIds,
            ),
        ":core:common" to
            ModuleBoundaryRule(
                allowedProjectDependencies = emptySet(),
                forbiddenGroupPrefixes = featureInfrastructureGroupPrefixes,
                forbiddenModules =
                    setOf(
                        "org.jetbrains.kotlinx" to "kotlinx-serialization",
                    ),
                forbiddenPluginIds =
                    forbiddenNonUiKmpPluginIds + "org.jetbrains.kotlin.plugin.serialization",
            ),
        ":core:security-api" to
            ModuleBoundaryRule(
                allowedProjectDependencies = setOf(":core:protocol"),
                forbiddenGroupPrefixes = featureInfrastructureGroupPrefixes + "org.jetbrains.kotlinx",
                forbiddenPluginIds =
                    forbiddenNonUiKmpPluginIds + "org.jetbrains.kotlin.plugin.serialization",
            ),
        ":feature:pairing:domain" to
            ModuleBoundaryRule(
                allowedProjectDependencies = emptySet(),
                forbiddenGroupPrefixes = featureInfrastructureGroupPrefixes + "org.jetbrains.kotlinx",
                forbiddenPluginIds = forbiddenNonUiKmpPluginIds + "org.jetbrains.kotlin.plugin.serialization",
            ),
        ":feature:pairing:presentation" to
            ModuleBoundaryRule(
                allowedProjectDependencies = setOf(":feature:pairing:domain"),
                forbiddenGroupPrefixes = featureInfrastructureGroupPrefixes + "org.jetbrains.kotlinx",
                forbiddenPluginIds = forbiddenNonUiKmpPluginIds + "org.jetbrains.kotlin.plugin.serialization",
            ),
        ":shared" to
            ModuleBoundaryRule(
                allowedProjectDependencies =
                    setOf(
                        ":core:model",
                        ":core:network",
                        ":core:common",
                        ":core:protocol",
                        ":core:security-api",
                        ":feature:pairing:domain",
                        ":feature:pairing:presentation",
                    ),
                forbiddenGroupPrefixes = nativeUiDependencyGroupPrefixes,
                forbiddenPluginIds = forbiddenNonUiKmpPluginIds,
            ),
    )

allprojects {
    val exactGeneratedKotlinPaths = exactGeneratedKotlinPathsByProject[path].orEmpty()

    apply(plugin = "dev.detekt")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    extensions.configure<KtlintExtension> {
        version.set(ktlintEngineVersion)
        android.set(false)
        ignoreFailures.set(false)
        outputToConsole.set(true)

        reporters {
            reporter(ReporterType.PLAIN)
            reporter(ReporterType.CHECKSTYLE)
            reporter(ReporterType.SARIF)
        }

        filter {
            exactGeneratedKotlinPaths.forEach(::exclude)
        }
    }

    pluginManager.withPlugin("com.android.application") {
        extensions.configure<KtlintExtension> {
            android.set(true)
        }
    }

    extensions.configure<DetektExtension> {
        source.setFrom(
            fileTree(projectDir) {
                include("src/**/*.kt")
                include("src/**/*.kts")
                include("*.gradle.kts")
                if (project == rootProject) {
                    include("settings.gradle.kts")
                }
                exclude("build/**")
            },
        )
        config.setFrom(rootProject.file("config/quality/detekt.yml"))
        buildUponDefaultConfig = true
        allRules = false
        ignoreFailures = false
        failOnSeverity = FailOnSeverity.Info
        basePath.set(rootProject.projectDir)
    }

    tasks.withType<Detekt>().configureEach {
        exactGeneratedKotlinPaths.forEach(::exclude)
        reports {
            checkstyle.required.set(true)
            html.required.set(true)
            markdown.required.set(true)
            sarif.required.set(true)
        }
    }
}

subprojects {
    if (!buildFile.exists()) return@subprojects

    val sourceProjectPath = path
    val boundaryRule =
        requireNotNull(featureModuleBoundaryRules[sourceProjectPath]) {
            "Declare a module-boundary rule for $sourceProjectPath before adding it to the build"
        }

    configurations.configureEach {
        dependencies.configureEach {
            val dependency = this
            if (dependency is ProjectDependency && dependency.path != sourceProjectPath) {
                require(dependency.path in boundaryRule.allowedProjectDependencies) {
                    "$sourceProjectPath may not depend on ${dependency.path}"
                }
            }

            dependency.group?.let { dependencyGroup ->
                val forbiddenByGroup =
                    boundaryRule.forbiddenGroupPrefixes.any(dependencyGroup::startsWith)
                val forbiddenByModule =
                    boundaryRule.forbiddenModules.any { (group, modulePrefix) ->
                        dependencyGroup == group && dependency.name.startsWith(modulePrefix)
                    }
                require(!forbiddenByGroup && !forbiddenByModule) {
                    "$sourceProjectPath may not depend on $dependencyGroup:${dependency.name}"
                }
            }
        }
    }

    boundaryRule.forbiddenPluginIds.forEach { pluginId ->
        pluginManager.withPlugin(pluginId) {
            error("$sourceProjectPath may not apply $pluginId")
        }
    }
}

val checkModuleGraph =
    tasks.register("checkModuleGraph") {
        group = "verification"
        description = "Verifies allowed project edges and forbidden layer dependencies."
    }

val qualityCheck =
    tasks.register("qualityCheck") {
        group = "verification"
        description = "Runs non-mutating Kotlin formatting and static-analysis checks."
        dependsOn(allprojects.map { it.tasks.named("ktlintCheck") })
        dependsOn(allprojects.map { it.tasks.named("detekt") })
        dependsOn(gradle.includedBuild("build-logic").task(":qualityCheck"))
        // Android exposes reliable typed aggregates. Detekt 2.0's KMP compilation tasks currently
        // report incomplete compiler resolution, so shared code uses generic detekt plus real
        // JVM, Android, and Apple compilation/link gates until that analyzer boundary is stable.
        dependsOn(
            allprojects.map { project ->
                project.tasks.matching { task ->
                    task.name == "detektMain" ||
                        task.name == "detektTest"
                }
            },
        )
    }

tasks.register("qualityFormat") {
    group = "formatting"
    description = "Formats handwritten Kotlin sources and Gradle Kotlin scripts with ktlint."
    dependsOn(allprojects.map { it.tasks.named("ktlintFormat") })
    dependsOn(gradle.includedBuild("build-logic").task(":qualityFormat"))
}

tasks.register("mobileCheck") {
    group = "verification"
    description = "Runs host-side KMP and Android checks available on the current OS."
    dependsOn(checkModuleGraph, qualityCheck)
    dependsOn(
        ":androidApp:test",
        ":androidApp:assembleDebug",
        ":androidApp:lintDevDebug",
    )
    dependsOn(
        allprojects.map { project ->
            project.tasks.matching { task ->
                task.name == "jvmTest" ||
                    task.name == "testAndroidHostTest"
            }
        },
    )
}

tasks.register("appleCheck") {
    group = "verification"
    description = "Runs KMP iOS Simulator tests, device test links, and umbrella framework links."
    dependsOn(
        allprojects.map { project ->
            project.tasks.matching { task ->
                task.name == "iosSimulatorArm64Test" ||
                    task.name == "linkDebugTestIosArm64"
            }
        },
    )
    dependsOn(
        ":shared:linkDebugFrameworkIosArm64",
        ":shared:linkDebugFrameworkIosSimulatorArm64",
    )
}
