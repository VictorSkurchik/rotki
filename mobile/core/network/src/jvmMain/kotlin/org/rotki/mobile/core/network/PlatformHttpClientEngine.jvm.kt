package org.rotki.mobile.core.network

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

internal actual fun createPlatformHttpClientEngine(): HttpClientEngine =
    OkHttp.create {
        config {
            followRedirects(false)
            followSslRedirects(false)
            retryOnConnectionFailure(false)
        }
    }
