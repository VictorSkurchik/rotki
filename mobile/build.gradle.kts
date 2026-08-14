import dev.detekt.gradle.Detekt
import dev.detekt.gradle.extensions.DetektExtension
import dev.detekt.gradle.extensions.FailOnSeverity
import org.gradle.api.artifacts.ProjectDependency
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
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
        ":core:testing" to
            setOf(
                "**/GeneratedProtocolFixtures.kt",
            ),
    )
val ktlintEngineVersion =
    libs.versions.ktlint.engine
        .get()

data class ModuleBoundaryRule(
    val allowedProjectDependencies: Set<String>,
    val allowedExternalModules: Set<Pair<String, String>> = emptySet(),
    val allowedTestExternalModules: Set<Pair<String, String>> = emptySet(),
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
val sourceSetDependencyBucketSuffixes =
    setOf(
        "Api",
        "Implementation",
        "CompileOnly",
        "RuntimeOnly",
    )
val featureModuleBoundaryRules =
    mapOf(
        ":androidApp" to
            ModuleBoundaryRule(
                allowedProjectDependencies =
                    setOf(
                        ":android:feature:pairing",
                        ":android:navigation",
                        ":android:platform",
                        ":shared",
                    ),
            ),
        ":android:feature:pairing" to
            ModuleBoundaryRule(
                allowedProjectDependencies = emptySet(),
                allowedExternalModules =
                    setOf(
                        "androidx.camera" to "camera-camera2",
                        "androidx.camera" to "camera-core",
                        "androidx.camera" to "camera-lifecycle",
                        "androidx.camera" to "camera-view",
                        "androidx.compose" to "compose-bom",
                        "androidx.compose.compiler" to "compiler",
                        "androidx.compose.runtime" to "runtime",
                        "androidx.compose.ui" to "ui",
                        "androidx.lifecycle" to "lifecycle-runtime-compose",
                        "com.google.mlkit" to "barcode-scanning",
                    ),
                allowedTestExternalModules =
                    setOf(
                        "junit" to "junit",
                    ),
                forbiddenGroupPrefixes =
                    setOf(
                        "androidx.",
                        "com.google.mlkit",
                        "io.insert-koin",
                        "io.ktor",
                        "junit",
                        "org.jetbrains.kotlinx",
                    ),
                forbiddenPluginIds =
                    setOf(
                        "com.android.application",
                        "com.android.kotlin.multiplatform.library",
                        "org.jetbrains.compose",
                        "org.jetbrains.kotlin.android",
                        "org.jetbrains.kotlin.multiplatform",
                        "org.jetbrains.kotlin.plugin.serialization",
                    ),
            ),
        ":android:navigation" to
            ModuleBoundaryRule(
                allowedProjectDependencies = emptySet(),
                forbiddenGroupPrefixes =
                    setOf(
                        "androidx.biometric",
                        "androidx.camera",
                        "androidx.room",
                        "com.google.mlkit",
                        "io.insert-koin",
                        "io.ktor",
                    ),
                forbiddenPluginIds =
                    setOf(
                        "com.android.application",
                        "com.android.kotlin.multiplatform.library",
                        "org.jetbrains.compose",
                        "org.jetbrains.kotlin.multiplatform",
                    ),
            ),
        ":android:platform" to
            ModuleBoundaryRule(
                allowedProjectDependencies =
                    setOf(
                        ":core:common",
                        ":core:protocol",
                        ":core:security-api",
                    ),
                allowedExternalModules =
                    setOf(
                        "org.jetbrains.kotlinx" to "kotlinx-coroutines-core",
                    ),
                allowedTestExternalModules =
                    setOf(
                        "androidx.test" to "core",
                        "androidx.test" to "runner",
                        "androidx.test.ext" to "junit",
                        "junit" to "junit",
                    ),
                forbiddenGroupPrefixes =
                    featureInfrastructureGroupPrefixes +
                        setOf(
                            "com.google.mlkit",
                            "junit",
                            "org.jetbrains.kotlinx",
                        ),
                forbiddenModules =
                    setOf(
                        "org.jetbrains.kotlinx" to "kotlinx-serialization",
                    ),
                forbiddenPluginIds =
                    setOf(
                        "com.android.application",
                        "com.android.kotlin.multiplatform.library",
                        "org.jetbrains.compose",
                        "org.jetbrains.kotlin.android",
                        "org.jetbrains.kotlin.multiplatform",
                        "org.jetbrains.kotlin.plugin.compose",
                        "org.jetbrains.kotlin.plugin.serialization",
                    ),
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
        ":core:testing" to
            ModuleBoundaryRule(
                allowedProjectDependencies = setOf(":core:protocol"),
                forbiddenGroupPrefixes = featureInfrastructureGroupPrefixes,
                forbiddenPluginIds = forbiddenNonUiKmpPluginIds,
            ),
        ":feature:pairing:data" to
            ModuleBoundaryRule(
                allowedProjectDependencies =
                    setOf(
                        ":core:common",
                        ":core:network",
                        ":core:protocol",
                        ":core:testing",
                        ":feature:pairing:domain",
                    ),
                forbiddenGroupPrefixes = nativeUiDependencyGroupPrefixes,
                forbiddenPluginIds = forbiddenNonUiKmpPluginIds,
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
                        ":core:testing",
                        ":feature:pairing:data",
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

    listOf(
        "com.android.application",
        "com.android.library",
    ).forEach { androidPluginId ->
        pluginManager.withPlugin(androidPluginId) {
            extensions.configure<KtlintExtension> {
                android.set(true)
            }
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
        val dependencyConfigurationName = name
        val isSourceSetDependencyBucket =
            isCanBeDeclared &&
                sourceSetDependencyBucketSuffixes.any(dependencyConfigurationName::endsWith)
        val isTestDependencyBucket =
            isSourceSetDependencyBucket &&
                dependencyConfigurationName.contains("test", ignoreCase = true)
        dependencies.configureEach {
            val dependency = this
            if (dependency is ProjectDependency && dependency.path != sourceProjectPath) {
                require(dependency.path in boundaryRule.allowedProjectDependencies) {
                    "$sourceProjectPath may not depend on ${dependency.path}"
                }
                require(
                    dependency.path != ":core:testing" ||
                        !isSourceSetDependencyBucket ||
                        dependencyConfigurationName.contains("test", ignoreCase = true),
                ) {
                    "$sourceProjectPath may depend on :core:testing only from a test configuration"
                }
            }

            dependency.group?.let { dependencyGroup ->
                val dependencyCoordinates = dependencyGroup to dependency.name
                val allowedByNarrowException =
                    dependencyCoordinates in boundaryRule.allowedExternalModules ||
                        (
                            isTestDependencyBucket &&
                                dependencyCoordinates in boundaryRule.allowedTestExternalModules
                        )
                val forbiddenByGroup =
                    boundaryRule.forbiddenGroupPrefixes.any(dependencyGroup::startsWith)
                val forbiddenByModule =
                    boundaryRule.forbiddenModules.any { (group, modulePrefix) ->
                        dependencyGroup == group && dependency.name.startsWith(modulePrefix)
                    }
                require(
                    allowedByNarrowException ||
                        (!forbiddenByGroup && !forbiddenByModule),
                ) {
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
        ":android:feature:pairing:assembleDebug",
        ":android:feature:pairing:lintDebug",
        ":android:feature:pairing:test",
        ":android:navigation:assembleDebug",
        ":android:navigation:lintDebug",
        ":android:navigation:test",
        ":android:platform:assembleDebug",
        ":android:platform:lintDebug",
        ":android:platform:test",
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
