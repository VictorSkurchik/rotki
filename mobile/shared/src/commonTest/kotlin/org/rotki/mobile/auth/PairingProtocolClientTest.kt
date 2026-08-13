package org.rotki.mobile.auth

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.rotki.mobile.auth.protocol.DeviceLabel
import org.rotki.mobile.auth.protocol.DeviceLabelParseOutcome
import org.rotki.mobile.core.network.createCompanionHttpClient
import org.rotki.mobile.core.protocol.EngineOrigin
import org.rotki.mobile.core.protocol.EngineOriginParseOutcome
import org.rotki.mobile.core.protocol.IdempotencyKey
import org.rotki.mobile.core.protocol.PairingCredential
import org.rotki.mobile.core.protocol.PairingId
import org.rotki.mobile.core.protocol.ProtocolValueParseOutcome
import org.rotki.mobile.core.protocol.X963PublicKey
import org.rotki.mobile.core.protocol.generated.CompanionPlatform
import org.rotki.mobile.core.protocol.generated.ProtocolHeaders
import org.rotki.mobile.core.protocol.testing.ProtocolFixtureData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class PairingProtocolClientTest {
    @Test
    fun `pairing route descriptors match the authored route matrix`() {
        val authored = ProtocolFixtureData.cases.getValue("route_matrix").jsonArray
            .map { element -> element.jsonObject }
            .associateBy { route -> route.getValue("id").jsonPrimitive.content }

        listOf(PairingProtocolRoutes.Discovery, PairingProtocolRoutes.Registration)
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
    fun `discovery is public and negotiates protocol one before key registration`(): Unit =
        runTest {
            val engine = MockEngine { request ->
                assertEquals(HttpMethod.Get, request.method)
                assertEquals("/api/1/companion/protocol", request.url.encodedPath)
                assertNull(request.headers[HttpHeaders.Authorization])
                assertNull(request.headers[ProtocolHeaders.Protocol])
                assertNull(request.headers[ProtocolHeaders.IdempotencyKey])
                respondJson(DISCOVERY_SUCCESS, HttpStatusCode.OK)
            }
            val client = PairingProtocolClient(createCompanionHttpClient(engine))
            try {
                assertEquals(
                    1,
                    assertIs<PairingDiscoveryOutcome.Compatible>(
                        client.discover(origin()),
                    ).selectedProtocolVersion,
                )
                assertEquals(1, engine.requestHistory.size)
            } finally {
                client.close()
            }
        }

    @Test
    fun `legacy discovery 404 and typed 426 are incompatible`(): Unit = runTest {
        listOf(
            HttpStatusCode.NotFound to "legacy",
            HttpStatusCode.UpgradeRequired to INCOMPATIBLE_FAILURE,
        ).forEach { (status, body) ->
            val client = PairingProtocolClient(
                createCompanionHttpClient(MockEngine { respondJson(body, status) }),
            )
            try {
                assertIs<PairingDiscoveryOutcome.Incompatible>(client.discover(origin()))
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `registration sends fixed authority and rejects response binding mismatch`(): Unit =
        runTest {
            val engine = MockEngine { request ->
                assertEquals(HttpMethod.Post, request.method)
                assertEquals("/api/1/companion/device-sessions", request.url.encodedPath)
                assertEquals("Bearer $PAIRING_CREDENTIAL", request.headers[HttpHeaders.Authorization])
                assertEquals("1", request.headers[ProtocolHeaders.Protocol])
                assertEquals(IDEMPOTENCY_KEY, request.headers[ProtocolHeaders.IdempotencyKey])
                assertEquals(ContentType.Application.Json, request.body.contentType)
                assertEquals(REGISTRATION_REQUEST, request.body.bodyText())
                respondJson(REGISTRATION_SUCCESS, HttpStatusCode.Created)
            }
            val client = PairingProtocolClient(createCompanionHttpClient(engine))
            try {
                assertIs<PairingRegistrationRemoteOutcome.Registered>(
                    client.register(registrationRequest()),
                )
                assertEquals(1, engine.requestHistory.size)
            } finally {
                client.close()
            }

            val mismatchClient = PairingProtocolClient(
                createCompanionHttpClient(
                    MockEngine {
                        respondJson(
                            REGISTRATION_SUCCESS.replace(
                                "\"device_label\":\"Victor's iPhone\"",
                                "\"device_label\":\"Other phone\"",
                            ),
                            HttpStatusCode.Created,
                        )
                    },
                ),
            )
            try {
                assertIs<PairingRegistrationRemoteOutcome.ContractFailure>(
                    mismatchClient.register(registrationRequest()),
                )
            } finally {
                mismatchClient.close()
            }
        }

    @Test
    fun `redirect is decoded once as a contract failure and never followed`(): Unit = runTest {
        val engine = MockEngine {
            respond(
                content = "",
                status = HttpStatusCode.Found,
                headers = headersOf(HttpHeaders.Location, "https://other.example/target"),
            )
        }
        val client = PairingProtocolClient(createCompanionHttpClient(engine))
        try {
            assertIs<PairingRegistrationRemoteOutcome.ContractFailure>(
                client.register(registrationRequest()),
            )
            assertEquals(1, engine.requestHistory.size)
        } finally {
            client.close()
        }
    }

    @Test
    fun `malformed success is a strict contract failure`(): Unit = runTest {
        val duplicate = REGISTRATION_SUCCESS.replace(
            "\"state\":\"authorized\"",
            "\"state\":\"authorized\",\"state\":\"authorized\"",
        )
        val client = PairingProtocolClient(
            createCompanionHttpClient(
                MockEngine { respondJson(duplicate, HttpStatusCode.Created) },
            ),
        )
        try {
            assertIs<PairingRegistrationRemoteOutcome.ContractFailure>(
                client.register(registrationRequest()),
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `success requires empty message and registration requires cache control no store`(): Unit =
        runTest {
            val discovery = PairingProtocolClient(
                createCompanionHttpClient(
                    MockEngine {
                        respondJson(
                            DISCOVERY_SUCCESS.replace("\"message\":\"\"", "\"message\":\"ok\""),
                            HttpStatusCode.OK,
                        )
                    },
                ),
            )
            try {
                assertIs<PairingDiscoveryOutcome.ContractFailure>(discovery.discover(origin()))
            } finally {
                discovery.close()
            }

            listOf(
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                headersOf(
                    HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                    HttpHeaders.CacheControl to listOf("private"),
                ),
                headersOf(
                    HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                    HttpHeaders.CacheControl to listOf("no-store", "no-store"),
                ),
            ).forEach { headers ->
                val registration = PairingProtocolClient(
                    createCompanionHttpClient(
                        MockEngine {
                            respond(
                                content = REGISTRATION_SUCCESS,
                                status = HttpStatusCode.Created,
                                headers = headers,
                            )
                        },
                    ),
                )
                try {
                    assertIs<PairingRegistrationRemoteOutcome.ContractFailure>(
                        registration.register(registrationRequest()),
                    )
                } finally {
                    registration.close()
                }
            }
        }

    @Test
    fun `rate limit requires one canonical integer Retry-After`(): Unit = runTest {
        val withoutHeader = PairingProtocolClient(
            createCompanionHttpClient(
                MockEngine { respondJson(RATE_LIMIT_FAILURE, HttpStatusCode.TooManyRequests) },
            ),
        )
        try {
            assertIs<PairingRegistrationRemoteOutcome.ContractFailure>(
                withoutHeader.register(registrationRequest()),
            )
        } finally {
            withoutHeader.close()
        }

        val withHeader = PairingProtocolClient(
            createCompanionHttpClient(
                MockEngine {
                    respond(
                        content = RATE_LIMIT_FAILURE,
                        status = HttpStatusCode.TooManyRequests,
                        headers = headersOf(
                            HttpHeaders.ContentType to listOf(
                                ContentType.Application.Json.toString(),
                            ),
                            HttpHeaders.RetryAfter to listOf("5"),
                        ),
                    )
                },
            ),
        )
        try {
            assertEquals(
                5L,
                assertIs<PairingRegistrationRemoteOutcome.Rejected>(
                    withHeader.register(registrationRequest()),
                ).retryAfterSeconds,
            )
        } finally {
            withHeader.close()
        }
    }

    private fun registrationRequest(): PairingRegistrationRequest = PairingRegistrationRequest(
        engineOrigin = origin(),
        pairingId = parsed(PairingId.parse(PAIRING_ID)),
        pairingCredential = parsed(PairingCredential.parse(PAIRING_CREDENTIAL)),
        selectedProtocolVersion = 1,
        idempotencyKey = parsed(IdempotencyKey.parse(IDEMPOTENCY_KEY)),
        deviceLabel = assertIs<DeviceLabelParseOutcome.Accepted>(
            DeviceLabel.parse("Victor's iPhone"),
        ).value,
        platform = CompanionPlatform.Ios,
        publicKey = parsed(X963PublicKey.parse(PUBLIC_KEY)),
    )

    private fun origin(): EngineOrigin = assertIs<EngineOriginParseOutcome.Accepted>(
        EngineOrigin.parse("https://rotki.example"),
    ).origin

    private fun <T> parsed(outcome: ProtocolValueParseOutcome<T>): T =
        assertIs<ProtocolValueParseOutcome.Accepted<T>>(outcome).value
}

private fun OutgoingContent.bodyText(): String =
    assertIs<OutgoingContent.ByteArrayContent>(this).bytes().decodeToString()

private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(
    body: String,
    status: HttpStatusCode,
) = respond(
    content = body,
    status = status,
    headers = if (status == HttpStatusCode.Created) {
        headersOf(
            HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
            HttpHeaders.CacheControl to listOf("no-store"),
        )
    } else {
        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    },
)

private const val PAIRING_ID: String = "AAECAwQFBgcICQoLDA0ODw"
private const val PAIRING_CREDENTIAL: String =
    "EBESExQVFhcYGRobHB0eHyAhIiMkJSYnKCkqKywtLi8"
private const val IDEMPOTENCY_KEY: String = "cHFyc3R1dnd4eXp7fH1-fw"
private const val PUBLIC_KEY: String =
    "BGsX0fLhLEJH-Lzm5WOkQPJ3A32BLeszoPShOUXYmMKWT-NC4v4af5uO5-tKfA-eFivOM1drMV7Oy7ZAaDe_UfU"
private const val DISCOVERY_SUCCESS: String =
    """{"result":{"supported_protocol_versions":[1],"capabilities":{"device_sessions":1}},"message":""}"""
private const val INCOMPATIBLE_FAILURE: String =
    """{"result":null,"message":"redacted","error":{"code":"incompatible_protocol","retryable":false,"action":"upgrade_engine"}}"""
private const val REGISTRATION_SUCCESS: String =
    """{"result":{"device_session":{"device_session_id":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8","device_label":"Victor's iPhone","platform":"ios","state":"authorized","paired_at":1786550300,"last_seen_at":null,"revoked_at":null}},"message":""}"""
private const val REGISTRATION_REQUEST: String =
    """{"pairing_id":"AAECAwQFBgcICQoLDA0ODw","device_label":"Victor's iPhone","platform":"ios","public_key_algorithm":"ecdsa-p256-sha256-p1363","public_key":"BGsX0fLhLEJH-Lzm5WOkQPJ3A32BLeszoPShOUXYmMKWT-NC4v4af5uO5-tKfA-eFivOM1drMV7Oy7ZAaDe_UfU"}"""
private const val RATE_LIMIT_FAILURE: String =
    """{"result":null,"message":"redacted","error":{"code":"rate_limited","retryable":true,"action":"retry_after"}}"""
