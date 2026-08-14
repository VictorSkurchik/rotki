package org.rotki.mobile.core.network

import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.plugins.plugin
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompanionHttpClientTest {
    @Test
    fun boundsWebSocketFramesAndQueues() {
        val client = createCompanionHttpClient(MockEngine { respondOk() })
        try {
            val configuration = client.plugin(WebSockets)
            assertEquals(65_536L, configuration.maxFrameSize)
            assertEquals(16, configuration.channelsConfig.incoming.capacity)
            assertEquals(16, configuration.channelsConfig.outgoing.capacity)
        } finally {
            client.close()
        }
    }

    @Test
    fun returnsNonSuccessResponsesWithoutThrowing(): Unit =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content = """{"result":null,"message":"unavailable"}""",
                        status = HttpStatusCode.ServiceUnavailable,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            val client = createCompanionHttpClient(engine)
            try {
                assertEquals(
                    HttpStatusCode.ServiceUnavailable,
                    client.get("https://rotki.example/api/1/companion/protocol").status,
                )
            } finally {
                client.close()
            }
        }

    @Test
    fun doesNotFollowCredentialBearingRedirects(): Unit =
        runTest {
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath == "/redirect") {
                        respond(
                            content = "",
                            status = HttpStatusCode.Found,
                            headers = headersOf(HttpHeaders.Location, "https://other.example/target"),
                        )
                    } else {
                        respondOk("unexpected")
                    }
                }
            val client = createCompanionHttpClient(engine)
            try {
                assertEquals(HttpStatusCode.Found, client.get("https://rotki.example/redirect").status)
                assertEquals(1, engine.requestHistory.size)
            } finally {
                client.close()
            }
        }

    @Test
    fun installsTheStrictSharedJsonConfiguration(): Unit =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content = """{"value":"ok","future":1}""",
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            val client = createCompanionHttpClient(engine)
            try {
                assertEquals(
                    JsonFixture("ok"),
                    client.get("https://rotki.example/value").body<JsonFixture>(),
                )
            } finally {
                client.close()
            }
        }

    @Test
    fun ownerCancellationStopsAnInFlightRequestWithoutRetry(): Unit =
        runTest {
            val handlerStarted = CompletableDeferred<Unit>()
            val handlerObservedCancellation = CompletableDeferred<Unit>()
            var handlerCalls = 0
            val engine =
                MockEngine {
                    handlerCalls += 1
                    handlerStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } catch (cancellation: CancellationException) {
                        handlerObservedCancellation.complete(Unit)
                        throw cancellation
                    }
                }
            val client = createCompanionHttpClient(engine)
            try {
                val foregroundOwnedRequest =
                    launch {
                        client.get("https://rotki.example/api/1/companion/protocol")
                    }
                handlerStarted.await()

                foregroundOwnedRequest.cancelAndJoin()

                handlerObservedCancellation.await()
                assertTrue(foregroundOwnedRequest.isCancelled)
                assertEquals(1, handlerCalls)
                assertTrue(engine.requestHistory.size <= 1)
            } finally {
                client.close()
            }
        }

    @Serializable
    private data class JsonFixture(
        val value: String,
    )
}
