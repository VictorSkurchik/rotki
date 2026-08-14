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
include(":android:navigation")
include(":core:common")
include(":core:model")
include(":core:network")
include(":core:protocol")
include(":core:security-api")
include(":core:testing")
include(":feature:pairing:data")
include(":feature:pairing:domain")
include(":feature:pairing:presentation")
include(":shared")
