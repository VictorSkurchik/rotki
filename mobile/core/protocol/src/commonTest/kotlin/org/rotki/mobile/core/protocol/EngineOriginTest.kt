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
    fun preservesKtorAcceptanceOfOddAsciiHostsAndIpv4Spellings() {
        listOf(
            "https://localhost",
            "https://example",
            "https://rotki.example.",
            "https://.rotki.example",
            "https://rotki..example",
            "https://-rotki.example",
            "https://rotki-.example",
            "https://rot_ki.example",
            "https://rotki.example|evil",
            "https://xn--rtki-5qa.example",
            "https://%",
            "https://%gg",
            "https://%72otki.example",
            "https://rotki%2eexample",
            "https://user%40rotki.example",
            "https://rotki.example%2fpath",
            "https://192.000.002.001",
            "https://127.1",
            "https://2130706433",
            "https://0x7f000001",
            "https://256.1.1.1",
            "https://rotki.example:1",
            "https://rotki.example:80",
            "https://rotki.example:65535",
        ).forEach(::assertAcceptedWithDerivedEndpoints)
    }

    @Test
    fun preservesKtorAcceptanceOfBracketedIpv6LikeAuthorities() {
        listOf(
            "https://[2001:db8::1]",
            "https://[2001:0db8:0:0:0:0:0:1]",
            "https://[::1]",
            "https://[::]",
            "https://[::ffff:192.0.2.1]",
            "https://[fe80::1%25en0]",
            "https://[fe80::1%en0]",
            "https://[2001:db8::1]:8443",
            "https://[2001:db8:::1]",
            "https://[gggg::1]",
            "https://[]",
            "https://[x]",
        ).forEach(::assertAcceptedWithDerivedEndpoints)
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
    fun preservesKtorRejectionCategoriesAndValidationPrecedence() {
        listOf(
            "" to EngineOriginRejection.MALFORMED,
            "http://rotki.example" to EngineOriginRejection.HTTPS_REQUIRED,
            "HTTPS://rotki.example" to EngineOriginRejection.HTTPS_REQUIRED,
            "https://ROTKI.example" to EngineOriginRejection.NON_CANONICAL,
            "https://" to EngineOriginRejection.NON_CANONICAL,
            "https://:8443" to EngineOriginRejection.NON_CANONICAL,
            "https://:443" to EngineOriginRejection.DEFAULT_PORT_FORBIDDEN,
            "https://rötki.example" to EngineOriginRejection.ASCII_REQUIRED,
            "https://rotki.example " to EngineOriginRejection.ASCII_REQUIRED,
            "https://rotki.example\n" to EngineOriginRejection.ASCII_REQUIRED,
            "https://rotki.example\t" to EngineOriginRejection.ASCII_REQUIRED,
            "https://rotki.example\u0000" to EngineOriginRejection.ASCII_REQUIRED,
            "https://rotki.example\u007f" to EngineOriginRejection.ASCII_REQUIRED,
            "https://user@rotki.example" to EngineOriginRejection.ORIGIN_ONLY,
            "https://rotki.example/" to EngineOriginRejection.ORIGIN_ONLY,
            "https://rotki.example/path" to EngineOriginRejection.ORIGIN_ONLY,
            "https://rotki.example?query" to EngineOriginRejection.ORIGIN_ONLY,
            "https://rotki.example#fragment" to EngineOriginRejection.ORIGIN_ONLY,
            "https://rotki.example\\path" to EngineOriginRejection.ORIGIN_ONLY,
            "https://\\%" to EngineOriginRejection.ORIGIN_ONLY,
            "https://rotki.example/Path" to EngineOriginRejection.NON_CANONICAL,
            "https://rotki.example%2Epath" to EngineOriginRejection.NON_CANONICAL,
            "https://rotki.example:443/path" to EngineOriginRejection.ORIGIN_ONLY,
            "https://rotki.example:443" to EngineOriginRejection.DEFAULT_PORT_FORBIDDEN,
            "https://rotki.example:0443" to EngineOriginRejection.DEFAULT_PORT_FORBIDDEN,
            "https://rotki.example:+443" to EngineOriginRejection.DEFAULT_PORT_FORBIDDEN,
            "https://rotki.example:01" to EngineOriginRejection.NON_CANONICAL,
            "https://rotki.example:0" to EngineOriginRejection.NON_CANONICAL,
            "https://rotki.example:65536" to EngineOriginRejection.NON_CANONICAL,
            "https://rotki.example:-1" to EngineOriginRejection.NON_CANONICAL,
            "https://rotki.example:" to EngineOriginRejection.MALFORMED,
            "https://rotki.example:abc" to EngineOriginRejection.MALFORMED,
            "https://rotki.example:8443\\path" to EngineOriginRejection.MALFORMED,
            "https://rotki.example:443:444" to EngineOriginRejection.MALFORMED,
            "https://2001:db8::1" to EngineOriginRejection.MALFORMED,
            "https://[2001:db8::1" to EngineOriginRejection.MALFORMED,
            "https://[2001:db8::1]:" to EngineOriginRejection.MALFORMED,
            "https://[2001:db8::1]:443" to EngineOriginRejection.DEFAULT_PORT_FORBIDDEN,
            "https://[2001:db8::1]:0443" to EngineOriginRejection.DEFAULT_PORT_FORBIDDEN,
        ).forEach { (candidate, reason) ->
            assertEquals(
                reason,
                assertIs<EngineOriginParseOutcome.Rejected>(
                    EngineOrigin.parse(candidate),
                    candidate,
                ).reason,
                candidate,
            )
        }
    }

    @Test
    fun preservesCurrentOriginLengthBoundaryBeforeAsciiValidation() {
        val maximum = "https://" + "a".repeat(2_040)
        assertEquals(2_048, maximum.length)
        assertAcceptedWithDerivedEndpoints(maximum)

        val overMaximum = "https://" + "a".repeat(2_041)
        assertEquals(
            EngineOriginRejection.MALFORMED,
            assertIs<EngineOriginParseOutcome.Rejected>(
                EngineOrigin.parse(overMaximum),
            ).reason,
        )
        assertEquals(
            EngineOriginRejection.MALFORMED,
            assertIs<EngineOriginParseOutcome.Rejected>(
                EngineOrigin.parse("ö".repeat(2_049)),
            ).reason,
        )
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

        val acceptedCandidate = "https://seeded-origin.example"
        val accepted =
            assertIs<EngineOriginParseOutcome.Accepted>(
                EngineOrigin.parse(acceptedCandidate),
            )
        assertFalse(accepted.toString().contains(acceptedCandidate))

        val rejectedCandidate = "https://seeded-origin.example/path"
        val rejected =
            assertIs<EngineOriginParseOutcome.Rejected>(
                EngineOrigin.parse(rejectedCandidate),
            )
        assertFalse(rejected.toString().contains(rejectedCandidate))
    }
}

private fun assertAcceptedWithDerivedEndpoints(candidate: String) {
    val origin =
        assertIs<EngineOriginParseOutcome.Accepted>(
            EngineOrigin.parse(candidate),
            candidate,
        ).origin
    assertEquals(candidate, origin.canonical, candidate)
    assertEquals("$candidate/api/1", origin.restApiBase, candidate)
    assertEquals(
        "wss://${candidate.removePrefix("https://")}/ws",
        origin.webSocketEndpoint,
        candidate,
    )
    assertEquals("EngineOrigin(redacted)", origin.toString())
    assertFalse(origin.toString().contains(candidate))
}
