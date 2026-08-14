package org.rotki.mobile.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class EngineOriginTest {
    @Test
    fun acceptsCanonicalHttpsOriginsAndDerivesBothEndpoints() {
        val defaultPort =
            assertIs<EngineOriginParseOutcome.Accepted>(
                EngineOrigin.parse("https://rotki.example"),
            ).origin
        assertEquals("https://rotki.example", defaultPort.canonical)
        assertEquals("https://rotki.example/api/1", defaultPort.restApiBase)
        assertEquals("wss://rotki.example/ws", defaultPort.webSocketEndpoint)
        assertEquals("EngineOrigin(redacted)", defaultPort.toString())
        assertFalse(defaultPort.toString().contains("rotki.example"))

        val explicitPort =
            assertIs<EngineOriginParseOutcome.Accepted>(
                EngineOrigin.parse("https://192.0.2.1:8443"),
            ).origin
        assertEquals("https://192.0.2.1:8443/api/1", explicitPort.restApiBase)
        assertEquals("wss://192.0.2.1:8443/ws", explicitPort.webSocketEndpoint)
    }

    @Test
    fun rejectsAnythingOtherThanAnExactCanonicalOrigin() {
        listOf(
            "",
            "http://rotki.example",
            "HTTPS://rotki.example",
            "https://ROTKI.example",
            "https://rotki.example/",
            "https://rotki.example/path",
            "https://user@rotki.example",
            "https://@rotki.example",
            "https://rotki.example?query",
            "https://rotki.example?",
            "https://rotki.example#fragment",
            "https://rotki.example#",
            "https://rotki.example:443",
            "https://rotki.example:0443",
            "https://rotki.example:0",
            "https://rotki.example:65536",
            "https://rotki.example:01",
            "https://rotki.example ",
            "https://rötki.example",
        ).forEach { candidate ->
            assertIs<EngineOriginParseOutcome.Rejected>(
                EngineOrigin.parse(candidate),
                "Expected rejection for $candidate",
            )
        }
    }

    @Test
    fun reportsSecurityRelevantRejectionCategoriesWithoutEchoingInput() {
        assertEquals(
            EngineOriginRejection.DEFAULT_PORT_FORBIDDEN,
            assertIs<EngineOriginParseOutcome.Rejected>(
                EngineOrigin.parse("https://rotki.example:443"),
            ).reason,
        )
        assertEquals(
            EngineOriginRejection.ASCII_REQUIRED,
            assertIs<EngineOriginParseOutcome.Rejected>(
                EngineOrigin.parse("https://rötki.example"),
            ).reason,
        )
    }
}
