pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "rotki-mobile"

include(":androidApp")
include(":core:common")
include(":core:model")
include(":core:security-api")
include(":feature:pairing:domain")
include(":feature:pairing:presentation")
include(":shared")
