package org.rotki.mobile.feature.authorization.data

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.rotki.mobile.core.network.createCompanionHttpClient
import org.rotki.mobile.core.protocol.generated.ProtocolClientInputLimits
import org.rotki.mobile.core.protocol.generated.ProtocolHeaders
import org.rotki.mobile.core.protocol.testing.ProtocolFixtureData
import org.rotki.mobile.feature.authorization.domain.AuthorizationRemoteFailure
import org.rotki.mobile.feature.authorization.domain.AuthorizationRemoteOutcome
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class AuthorizationProtocolClientTest {
    @Test
    fun `route descriptors match authored challenge and proof matrix`() {
        val authored =
            ProtocolFixtureData.cases
                .getValue("route_matrix")
                .jsonArray
                .map { element -> element.jsonObject }
                .associateBy { route -> route.getValue("id").jsonPrimitive.content }

        listOf(AuthorizationProtocolRoutes.Challenge, AuthorizationProtocolRoutes.AccessSession)
            .forEach { route ->
                val expected = authored.getValue(route.id)
                assertEquals(expected.getValue("method").jsonPrimitive.content, route.method.value)
                assertEquals(expected.getValue("path").jsonPrimitive.content, route.path)
                assertEquals(
                    expected.getValue("success_status").jsonPrimitive.int,
                    route.successStatusCode,
                )
            }
    }

    @Test
    fun `MockEngine receives exact non-replayable challenge and proof requests`() =
        runTest {
            val challengeFixture = ProtocolFixtureData.successExample("create_challenge")
            val accessFixture = ProtocolFixtureData.successExample("create_access_session")
            val engine =
                MockEngine { request ->
                    val fixture =
                        when (request.url.encodedPath) {
                            "/api/1/companion/challenges" -> challengeFixture
                            "/api/1/companion/access-sessions" -> accessFixture
                            else -> error("Unexpected authorization route")
                        }
                    assertEquals(HttpMethod.Post, request.method)
                    assertEquals("1", request.headers[ProtocolHeaders.Protocol])
                    assertEquals(
                        listOf(ContentType.Application.Json.toString()),
                        request.headers.getAll(HttpHeaders.Accept),
                    )
                    assertNull(request.headers[HttpHeaders.Authorization])
                    assertNull(request.headers[ProtocolHeaders.IdempotencyKey])
                    val body = assertIs<OutgoingContent.ByteArrayContent>(request.body)
                    assertEquals(ContentType.Application.Json, body.contentType)
                    val expectedBody =
                        fixture
                            .getValue("request")
                            .jsonObject
                            .getValue("body")
                            .toString()
                            .encodeToByteArray()
                    assertEquals(expectedBody.size.toLong(), body.contentLength)
                    assertContentEquals(expectedBody, body.bytes())
                    assertEquals("AuthorizationJsonContent(redacted)", body.toString())
                    listOf(
                        TestAuthorizationValues.DEVICE_SESSION_ID,
                        TestAuthorizationValues.CHALLENGE_ID,
                        TestAuthorizationValues.SIGNATURE,
                    ).forEach { value -> assertFalse(value in body.toString()) }
                    respondJson(
                        fixture.getValue("response").toString(),
                        HttpStatusCode.Created,
                        noStore = true,
                    )
                }
            val client = AuthorizationProtocolClient(createCompanionHttpClient(engine))
            try {
                val challenge =
                    assertIs<AuthorizationRemoteOutcome.Success<*>>(
                        client.requestChallenge(
                            TestAuthorizationValues.origin(),
                            TestAuthorizationValues.deviceSessionId(),
                            selectedProtocolVersion = 1,
                        ),
                    )
                assertEquals("Success(redacted)", challenge.toString())
                assertFalse(TestAuthorizationValues.CHALLENGE_ID in challenge.toString())

                val session =
                    assertIs<AuthorizationRemoteOutcome.Success<*>>(
                        client.submitProof(
                            TestAuthorizationValues.origin(),
                            TestAuthorizationValues.deviceSessionId(),
                            TestAuthorizationValues.challengeId(),
                            TestAuthorizationValues.signature(),
                            selectedProtocolVersion = 1,
                        ),
                    )
                assertEquals("Success(redacted)", session.toString())
                assertFalse(TestAuthorizationValues.ACCESS_CREDENTIAL in session.toString())
                assertEquals(2, engine.requestHistory.size)
                assertEquals("AuthorizationProtocolClient(redacted)", client.toString())
            } finally {
                client.close()
            }
        }

    @Test
    fun `success requires no-store strict JSON and bounded canonical values`() =
        runTest {
            val valid = ProtocolFixtureData.successExample("create_challenge").getValue("response").toString()
            val oversized =
                " ".repeat(ProtocolClientInputLimits.MaximumControlResponseBytes + 1)
            val cases =
                listOf(
                    ResponseCase(valid, noStore = false),
                    ResponseCase(
                        valid.replace("\"challenge_id\":", "\"challenge_id\":\"duplicate\",\"challenge_id\":"),
                        true,
                    ),
                    ResponseCase(
                        valid.replace(TestAuthorizationValues.CHALLENGE_ID, "not-canonical="),
                        true,
                    ),
                    ResponseCase(oversized, true),
                )
            cases.forEach { responseCase ->
                val engine =
                    MockEngine {
                        respondJson(
                            responseCase.body,
                            HttpStatusCode.Created,
                            noStore = responseCase.noStore,
                        )
                    }
                val client = AuthorizationProtocolClient(createCompanionHttpClient(engine))
                try {
                    assertEquals(
                        AuthorizationRemoteOutcome.ContractFailure,
                        client.requestChallenge(
                            TestAuthorizationValues.origin(),
                            TestAuthorizationValues.deviceSessionId(),
                            selectedProtocolVersion = 1,
                        ),
                    )
                    assertEquals(1, engine.requestHistory.size)
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun `access success rejects malformed envelopes headers and status`() =
        runTest {
            val valid =
                ProtocolFixtureData
                    .successExample("create_access_session")
                    .getValue("response")
                    .toString()
            val responseCases =
                listOf(
                    AccessResponseCase(
                        body = valid,
                        status = HttpStatusCode.Created,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    ),
                    AccessResponseCase(
                        body = valid,
                        status = HttpStatusCode.Created,
                        headers =
                            headersOf(
                                HttpHeaders.ContentType to
                                    listOf(ContentType.Application.Json.toString()),
                                HttpHeaders.CacheControl to listOf("private"),
                            ),
                    ),
                    AccessResponseCase(
                        body = valid,
                        status = HttpStatusCode.Created,
                        headers =
                            headersOf(
                                HttpHeaders.ContentType to
                                    listOf(ContentType.Application.Json.toString()),
                                HttpHeaders.CacheControl to listOf("no-store", "no-store"),
                            ),
                    ),
                    AccessResponseCase(
                        body = valid.replace("\"message\":\"\"", "\"message\":\"not-empty\""),
                        status = HttpStatusCode.Created,
                        headers = successHeaders(),
                    ),
                    AccessResponseCase(
                        body =
                            valid.replace(
                                "\"access_session_credential\":",
                                "\"access_session_credential\":\"duplicate\",\"access_session_credential\":",
                            ),
                        status = HttpStatusCode.Created,
                        headers = successHeaders(),
                    ),
                    AccessResponseCase(
                        body = valid,
                        status = HttpStatusCode.OK,
                        headers = successHeaders(),
                    ),
                )
            responseCases.forEach { responseCase ->
                val engine =
                    MockEngine {
                        respond(
                            content = responseCase.body,
                            status = responseCase.status,
                            headers = responseCase.headers,
                        )
                    }
                val client = AuthorizationProtocolClient(createCompanionHttpClient(engine))
                try {
                    assertEquals(
                        AuthorizationRemoteOutcome.ContractFailure,
                        client.submitProof(
                            TestAuthorizationValues.origin(),
                            TestAuthorizationValues.deviceSessionId(),
                            TestAuthorizationValues.challengeId(),
                            TestAuthorizationValues.signature(),
                            selectedProtocolVersion = 1,
                        ),
                    )
                    assertEquals(1, engine.requestHistory.size)
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun `typed authorization failures map to coarse redacted outcomes on both routes`() =
        runTest {
            val cases =
                listOf(
                    FailureCase(400, "invalid_request", "none", AuthorizationRemoteFailure.INVALID_REQUEST),
                    FailureCase(401, "not_authorized", "pair_again", AuthorizationRemoteFailure.NOT_AUTHORIZED),
                    FailureCase(
                        409,
                        "profile_mismatch",
                        "open_bound_profile",
                        AuthorizationRemoteFailure.PROFILE_MISMATCH,
                    ),
                    FailureCase(
                        410,
                        "challenge_unavailable",
                        "request_challenge",
                        AuthorizationRemoteFailure.CHALLENGE_UNAVAILABLE,
                    ),
                    FailureCase(423, "locked_engine", "unlock_full_client", AuthorizationRemoteFailure.LOCKED_ENGINE),
                    FailureCase(
                        426,
                        "incompatible_protocol",
                        "upgrade_engine",
                        AuthorizationRemoteFailure.INCOMPATIBLE_PROTOCOL,
                    ),
                    FailureCase(
                        429,
                        "rate_limited",
                        "retry_after",
                        AuthorizationRemoteFailure.RATE_LIMITED,
                        retryable = true,
                    ),
                    FailureCase(
                        500,
                        "unexpected_engine_error",
                        "none",
                        AuthorizationRemoteFailure.UNEXPECTED_ENGINE_ERROR,
                    ),
                )
            cases.forEach { failureCase ->
                val engine =
                    MockEngine {
                        respondJson(
                            failureCase.body(),
                            HttpStatusCode.fromValue(failureCase.statusCode),
                            retryAfterSeconds = failureCase.retryAfterSeconds,
                        )
                    }
                val client = AuthorizationProtocolClient(createCompanionHttpClient(engine))
                try {
                    val challengeOutcome =
                        assertIs<AuthorizationRemoteOutcome.Rejected>(
                            client.requestChallenge(
                                TestAuthorizationValues.origin(),
                                TestAuthorizationValues.deviceSessionId(),
                                selectedProtocolVersion = 1,
                            ),
                        )
                    val proofOutcome =
                        assertIs<AuthorizationRemoteOutcome.Rejected>(
                            client.submitProof(
                                TestAuthorizationValues.origin(),
                                TestAuthorizationValues.deviceSessionId(),
                                TestAuthorizationValues.challengeId(),
                                TestAuthorizationValues.signature(),
                                selectedProtocolVersion = 1,
                            ),
                        )
                    listOf(challengeOutcome, proofOutcome).forEach { outcome ->
                        assertEquals(failureCase.expected, outcome.failure)
                        assertEquals(failureCase.retryAfterSeconds, outcome.retryAfterSeconds)
                        assertEquals("Rejected(redacted)", outcome.toString())
                        assertFalse(TestAuthorizationValues.ACCESS_CREDENTIAL in outcome.toString())
                    }
                    assertEquals(2, engine.requestHistory.size)
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun `rate limit requires one canonical integer Retry-After on both routes`() =
        runTest {
            val body =
                FailureCase(
                    statusCode = 429,
                    code = "rate_limited",
                    action = "retry_after",
                    expected = AuthorizationRemoteFailure.RATE_LIMITED,
                    retryable = true,
                ).body()
            val invalidHeaders =
                listOf(
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    headersOf(
                        HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                        HttpHeaders.RetryAfter to listOf("1", "1"),
                    ),
                    headersOf(
                        HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                        HttpHeaders.RetryAfter to listOf("01"),
                    ),
                    headersOf(
                        HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                        HttpHeaders.RetryAfter to listOf("-1"),
                    ),
                )
            invalidHeaders.forEach { headers ->
                val engine =
                    MockEngine {
                        respond(
                            content = body,
                            status = HttpStatusCode.TooManyRequests,
                            headers = headers,
                        )
                    }
                val client = AuthorizationProtocolClient(createCompanionHttpClient(engine))
                try {
                    assertEquals(
                        AuthorizationRemoteOutcome.ContractFailure,
                        client.requestChallenge(
                            TestAuthorizationValues.origin(),
                            TestAuthorizationValues.deviceSessionId(),
                            selectedProtocolVersion = 1,
                        ),
                    )
                    assertEquals(
                        AuthorizationRemoteOutcome.ContractFailure,
                        client.submitProof(
                            TestAuthorizationValues.origin(),
                            TestAuthorizationValues.deviceSessionId(),
                            TestAuthorizationValues.challengeId(),
                            TestAuthorizationValues.signature(),
                            selectedProtocolVersion = 1,
                        ),
                    )
                    assertEquals(2, engine.requestHistory.size)
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun `transport loss and redirects are never retransmitted`() =
        runTest {
            var attempts = 0
            val transportEngine =
                MockEngine {
                    attempts += 1
                    throw IOException("seeded transport failure")
                }
            val transportClient = AuthorizationProtocolClient(createCompanionHttpClient(transportEngine))
            try {
                assertEquals(
                    AuthorizationRemoteOutcome.PreResponseTransportFailure,
                    transportClient.requestChallenge(
                        TestAuthorizationValues.origin(),
                        TestAuthorizationValues.deviceSessionId(),
                        selectedProtocolVersion = 1,
                    ),
                )
                assertEquals(1, attempts)
            } finally {
                transportClient.close()
            }

            var proofAttempts = 0
            val proofTransportEngine =
                MockEngine {
                    proofAttempts += 1
                    throw IOException("seeded proof transport failure")
                }
            val proofTransportClient =
                AuthorizationProtocolClient(createCompanionHttpClient(proofTransportEngine))
            try {
                assertEquals(
                    AuthorizationRemoteOutcome.PreResponseTransportFailure,
                    proofTransportClient.submitProof(
                        TestAuthorizationValues.origin(),
                        TestAuthorizationValues.deviceSessionId(),
                        TestAuthorizationValues.challengeId(),
                        TestAuthorizationValues.signature(),
                        selectedProtocolVersion = 1,
                    ),
                )
                assertEquals(1, proofAttempts)
            } finally {
                proofTransportClient.close()
            }

            val redirectEngine =
                MockEngine {
                    respond(
                        content = "",
                        status = HttpStatusCode.TemporaryRedirect,
                        headers = headersOf(HttpHeaders.Location, "https://other.example/target"),
                    )
                }
            val redirectClient = AuthorizationProtocolClient(createCompanionHttpClient(redirectEngine))
            try {
                assertEquals(
                    AuthorizationRemoteOutcome.ContractFailure,
                    redirectClient.requestChallenge(
                        TestAuthorizationValues.origin(),
                        TestAuthorizationValues.deviceSessionId(),
                        selectedProtocolVersion = 1,
                    ),
                )
                assertEquals(1, redirectEngine.requestHistory.size)
            } finally {
                redirectClient.close()
            }

            val proofRedirectEngine =
                MockEngine {
                    respond(
                        content = "",
                        status = HttpStatusCode.TemporaryRedirect,
                        headers = headersOf(HttpHeaders.Location, "https://other.example/target"),
                    )
                }
            val proofRedirectClient =
                AuthorizationProtocolClient(createCompanionHttpClient(proofRedirectEngine))
            try {
                assertEquals(
                    AuthorizationRemoteOutcome.ContractFailure,
                    proofRedirectClient.submitProof(
                        TestAuthorizationValues.origin(),
                        TestAuthorizationValues.deviceSessionId(),
                        TestAuthorizationValues.challengeId(),
                        TestAuthorizationValues.signature(),
                        selectedProtocolVersion = 1,
                    ),
                )
                assertEquals(1, proofRedirectEngine.requestHistory.size)
            } finally {
                proofRedirectClient.close()
            }
        }

    @Test
    fun `response-started loss is classified without replay on both routes`() =
        runTest {
            suspend fun assertCompleteResponseFailure(submitProof: Boolean) {
                var attempts = 0
                val engine =
                    MockEngine {
                        attempts += 1
                        val channel = ByteReadChannel("partial".encodeToByteArray())
                        channel.cancel(IOException("seeded response body failure"))
                        respond(
                            content = channel,
                            status = HttpStatusCode.Created,
                            headers = successHeaders(),
                        )
                    }
                val client = AuthorizationProtocolClient(createCompanionHttpClient(engine))
                try {
                    val outcome =
                        if (submitProof) {
                            client.submitProof(
                                TestAuthorizationValues.origin(),
                                TestAuthorizationValues.deviceSessionId(),
                                TestAuthorizationValues.challengeId(),
                                TestAuthorizationValues.signature(),
                                selectedProtocolVersion = 1,
                            )
                        } else {
                            client.requestChallenge(
                                TestAuthorizationValues.origin(),
                                TestAuthorizationValues.deviceSessionId(),
                                selectedProtocolVersion = 1,
                            )
                        }
                    assertEquals(AuthorizationRemoteOutcome.CompleteResponseTransportFailure, outcome)
                    assertEquals(1, attempts)
                } finally {
                    client.close()
                }
            }

            assertCompleteResponseFailure(submitProof = false)
            assertCompleteResponseFailure(submitProof = true)
        }
}

private data class ResponseCase(
    val body: String,
    val noStore: Boolean,
)

private data class AccessResponseCase(
    val body: String,
    val status: HttpStatusCode,
    val headers: io.ktor.http.Headers,
)

private data class FailureCase(
    val statusCode: Int,
    val code: String,
    val action: String,
    val expected: AuthorizationRemoteFailure,
    val retryable: Boolean = false,
) {
    val retryAfterSeconds: Long? = if (statusCode == 429) 1 else null

    fun body(): String =
        """{"result":null,"message":"redacted","error":{"code":"$code","retryable":$retryable,"action":"$action"}}"""
}

private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(
    body: String,
    status: HttpStatusCode,
    noStore: Boolean = false,
    retryAfterSeconds: Long? = null,
) = respond(
    content = body,
    status = status,
    headers =
        when {
            noStore && retryAfterSeconds != null -> {
                headersOf(
                    HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                    HttpHeaders.CacheControl to listOf("no-store"),
                    HttpHeaders.RetryAfter to listOf(retryAfterSeconds.toString()),
                )
            }

            noStore -> {
                headersOf(
                    HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                    HttpHeaders.CacheControl to listOf("no-store"),
                )
            }

            retryAfterSeconds != null -> {
                headersOf(
                    HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                    HttpHeaders.RetryAfter to listOf(retryAfterSeconds.toString()),
                )
            }

            else -> {
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            }
        },
)

private fun successHeaders(): io.ktor.http.Headers =
    headersOf(
        HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
        HttpHeaders.CacheControl to listOf("no-store"),
    )
