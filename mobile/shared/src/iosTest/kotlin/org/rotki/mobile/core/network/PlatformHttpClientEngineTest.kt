package org.rotki.mobile.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLSessionConfiguration

class PlatformHttpClientEngineTest {
    @Test
    fun DarwinSessionDoesNotCacheCompanionResponsesOrCredentials(): Unit {
        val configuration = NSURLSessionConfiguration.defaultSessionConfiguration()

        disableCompanionUrlCaching(configuration)

        assertNull(configuration.URLCache)
        assertNull(configuration.URLCredentialStorage)
        assertNull(configuration.HTTPCookieStorage)
        assertEquals(false, configuration.HTTPShouldSetCookies)
        assertEquals(
            NSURLRequestReloadIgnoringLocalCacheData,
            configuration.requestCachePolicy,
        )
    }
}
