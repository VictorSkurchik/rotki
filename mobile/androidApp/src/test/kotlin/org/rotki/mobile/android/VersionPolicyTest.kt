package org.rotki.mobile.android

import org.junit.Assert.assertEquals
import org.junit.Test

class VersionPolicyTest {
    @Test
    fun versionUsesFlavorThenBuildTypeSuffix() {
        val flavorSuffix =
            when (BuildConfig.FLAVOR) {
                "dev" -> "-dev"
                "stage" -> "-stage"
                "prod" -> ""
                else -> error("Unexpected flavor: ${BuildConfig.FLAVOR}")
            }
        val applicationIdSuffix =
            when (BuildConfig.FLAVOR) {
                "dev" -> ".dev"
                "stage" -> ".stage"
                "prod" -> ""
                else -> error("Unexpected flavor: ${BuildConfig.FLAVOR}")
            }
        val buildTypeSuffix =
            when (BuildConfig.BUILD_TYPE) {
                "debug" -> "-debug"
                "release" -> "-release"
                else -> error("Unexpected build type: ${BuildConfig.BUILD_TYPE}")
            }

        assertEquals("1.0.0$flavorSuffix$buildTypeSuffix", BuildConfig.VERSION_NAME)
        assertEquals("com.rotki.companion$applicationIdSuffix", BuildConfig.APPLICATION_ID)
    }
}
