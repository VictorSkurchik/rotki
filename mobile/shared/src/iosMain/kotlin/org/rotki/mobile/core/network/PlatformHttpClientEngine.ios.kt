package org.rotki.mobile.core.network

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLSessionConfiguration

internal actual fun createPlatformHttpClientEngine(): HttpClientEngine = Darwin.create {
    configureSession(::disableCompanionUrlCaching)
    configureRequest(::disableCompanionRequestCaching)
}

internal fun disableCompanionUrlCaching(configuration: NSURLSessionConfiguration): Unit {
    configuration.URLCache = null
    configuration.URLCredentialStorage = null
    configuration.HTTPCookieStorage = null
    configuration.HTTPShouldSetCookies = false
    configuration.requestCachePolicy = NSURLRequestReloadIgnoringLocalCacheData
}

private fun disableCompanionRequestCaching(request: NSMutableURLRequest): Unit {
    request.setCachePolicy(NSURLRequestReloadIgnoringLocalCacheData)
}
