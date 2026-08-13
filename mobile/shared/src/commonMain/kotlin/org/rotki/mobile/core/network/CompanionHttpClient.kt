package org.rotki.mobile.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.ChannelOverflow
import org.rotki.mobile.core.protocol.generated.ProtocolClientInputLimits

internal fun createCompanionHttpClient(engine: HttpClientEngine): HttpClient =
    HttpClient(engine) {
        expectSuccess = false
        followRedirects = false

        install(ContentNegotiation) {
            json(CompanionJson)
        }
        install(WebSockets) {
            maxFrameSize = ProtocolClientInputLimits.MaximumWebSocketEventBytes
            channels {
                incoming = bounded(
                    ProtocolClientInputLimits.MaximumWebSocketBufferedFrames,
                    ChannelOverflow.CLOSE,
                )
                outgoing = bounded(
                    ProtocolClientInputLimits.MaximumWebSocketBufferedFrames,
                    ChannelOverflow.CLOSE,
                )
            }
        }
    }

internal fun createPlatformCompanionHttpClient(): HttpClient =
    createCompanionHttpClient(createPlatformHttpClientEngine())

internal expect fun createPlatformHttpClientEngine(): HttpClientEngine
