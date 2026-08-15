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
include(":android:feature:pairing")
include(":android:navigation")
include(":android:platform")
include(":core:common")
include(":core:model")
include(":core:network")
include(":core:protocol")
include(":core:security-api")
include(":core:testing")
include(":feature:authorization:application")
include(":feature:authorization:data")
include(":feature:authorization:domain")
include(":feature:pairing:data")
include(":feature:pairing:domain")
include(":feature:pairing:presentation")
include(":shared")
