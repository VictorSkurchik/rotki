package org.rotki.mobile.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.websocket.ChannelOverflow
import org.rotki.mobile.core.protocol.generated.ProtocolClientInputLimits

internal fun createCompanionHttpClient(engine: HttpClientEngine): HttpClient =
    HttpClient(engine) {
        expectSuccess = false
        followRedirects = false

        install(HttpTimeout) {
            requestTimeoutMillis = COMPANION_REQUEST_TIMEOUT_MILLIS
            connectTimeoutMillis = COMPANION_CONNECT_TIMEOUT_MILLIS
            socketTimeoutMillis = COMPANION_SOCKET_TIMEOUT_MILLIS
        }

        install(WebSockets) {
            maxFrameSize = ProtocolClientInputLimits.MaximumWebSocketEventBytes
            channels {
                incoming =
                    bounded(
                        ProtocolClientInputLimits.MaximumWebSocketBufferedFrames,
                        ChannelOverflow.CLOSE,
                    )
                outgoing =
                    bounded(
                        ProtocolClientInputLimits.MaximumWebSocketBufferedFrames,
                        ChannelOverflow.CLOSE,
                    )
            }
        }
    }

internal fun createPlatformCompanionHttpClient(): HttpClient =
    createCompanionHttpClient(createPlatformHttpClientEngine())

internal expect fun createPlatformHttpClientEngine(): HttpClientEngine

private const val COMPANION_REQUEST_TIMEOUT_MILLIS: Long = 15_000L
private const val COMPANION_CONNECT_TIMEOUT_MILLIS: Long = 10_000L
private const val COMPANION_SOCKET_TIMEOUT_MILLIS: Long = 10_000L
