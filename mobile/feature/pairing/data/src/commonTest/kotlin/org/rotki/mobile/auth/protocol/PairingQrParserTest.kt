package org.rotki.mobile.auth.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.rotki.mobile.core.ports.Clock
import org.rotki.mobile.core.protocol.testing.ProtocolFixtureData
import org.rotki.mobile.feature.pairing.domain.PairingAdmission
import org.rotki.mobile.feature.pairing.domain.PairingSessionPort
import org.rotki.mobile.feature.pairing.domain.PairingSubmissionOutcome
import org.rotki.mobile.feature.pairing.domain.PairingSubmissionRejection
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PairingQrParserTest {
    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun `all canonical Pairing QR acceptance cases execute unchanged`() {
        val cases = ProtocolFixtureData.golden.getValue("pairing_qr_cases").jsonArray
        assertEquals(10, cases.size)
        cases.forEach { element ->
            val case = element.jsonObject
            val raw =
                when {
                    "wire_utf8" in case -> case.string("wire_utf8").encodeToByteArray()
                    "wire_base64" in case -> Base64.Default.decode(case.string("wire_base64"))
                    else -> buildWire(case.getValue("wire_builder").jsonObject)
                }
            val now = case.long("now")
            val accepted = case.boolean("accepted")
            val result = PairingQrParser(Clock { now }).parse(raw)

            if (accepted) {
                assertIs<PairingQrParseOutcome.Accepted>(result, case.string("id"))
            } else {
                assertIs<PairingQrParseOutcome.Rejected>(result, case.string("id"))
            }
        }
    }

    @Test
    fun `duplicate members are rejected recursively including escaped-equivalent keys`() {
        val valid =
            ProtocolFixtureData.golden
                .getValue("pairing_qr_cases")
                .jsonArray
                .first()
                .jsonObject
                .string("wire_utf8")
        val nestedUnknown = valid.dropLast(1) + ",\"future\":{\"a\":1,\"\\u0061\":2}}"

        assertEquals(
            PairingQrRejection.DUPLICATE_MEMBER,
            assertIs<PairingQrParseOutcome.Rejected>(
                PairingQrParser(Clock { 0 }).parse(nestedUnknown.encodeToByteArray()),
            ).reason,
        )
    }

    @Test
    fun `all origin-only violations remain rejected`() {
        val valid =
            ProtocolFixtureData.golden
                .getValue("pairing_qr_cases")
                .jsonArray
                .first()
                .jsonObject
                .string("wire_utf8")
        listOf(
            "https://user@rotki.example",
            "https://rotki.example/path",
            "https://rotki.example?query=1",
            "https://rotki.example#fragment",
            "https://rotki.example/",
            "https://rotki.example:443",
        ).forEach { origin ->
            val wire = valid.replace("https://rotki.example", origin)
            assertIs<PairingQrParseOutcome.Rejected>(
                PairingQrParser(Clock { 0 }).parse(wire.encodeToByteArray()),
                origin,
            )
        }
    }

    @Test
    fun `device labels reject lone UTF-16 surrogates`() {
        assertNull(DeviceLabel.parse("bad\uD800label"))
        assertNull(DeviceLabel.parse("bad\uDC00label"))
        assertNull(DeviceLabel.parse("bad\uDB40\uDC01label"))
        assertNotNull(DeviceLabel.parse("valid 😀 label"))
    }

    @Test
    fun `excessive JSON nesting is rejected without recursing unboundedly`() {
        val valid =
            ProtocolFixtureData.golden
                .getValue("pairing_qr_cases")
                .jsonArray
                .first()
                .jsonObject
                .string("wire_utf8")
        val nested =
            valid.dropLast(1) + ",\"future\":" +
                "[".repeat(65) + "0" + "]".repeat(65) + "}"
        assertIs<PairingQrParseOutcome.Rejected>(
            PairingQrParser(Clock { 0 }).parse(nested.encodeToByteArray()),
        )
    }

    @Test
    fun `submission gateway and accepted QR redact all authority`() {
        val raw =
            ProtocolFixtureData.golden
                .getValue("pairing_qr_cases")
                .jsonArray
                .first()
                .jsonObject
                .string("wire_utf8")
        val session = RecordingPairingSession()
        val gateway = createPairingSubmissionGateway(session, Clock { 0 })

        assertIs<PairingSubmissionOutcome.Accepted>(gateway.submit(raw))

        val pairingQr = requireNotNull(session.admitted)
        assertEquals("PairingSubmissionDataGateway(redacted)", gateway.toString())
        assertEquals("PairingQr(redacted)", pairingQr.toString())
        listOf(
            pairingQr.engineOrigin.restApiBase,
            pairingQr.pairingId.encoded,
            pairingQr.pairingCredential.encoded,
            raw,
        ).forEach { authority ->
            assertFalse(authority in gateway.toString())
            assertFalse(authority in pairingQr.toString())
        }
    }

    @Test
    fun `submission gateway maps parser and session results without retaining authority`() {
        val valid =
            ProtocolFixtureData.golden
                .getValue("pairing_qr_cases")
                .jsonArray
                .first()
                .jsonObject
                .string("wire_utf8")
        val ignoredGateway =
            createPairingSubmissionGateway(
                RecordingPairingSession(PairingAdmission.IGNORED),
                Clock { 1_786_550_399 },
            )

        assertIs<PairingSubmissionOutcome.Ignored>(ignoredGateway.submit(valid))
        assertEquals(
            PairingSubmissionRejection.MALFORMED,
            assertIs<PairingSubmissionOutcome.Rejected>(ignoredGateway.submit("seeded-secret"))
                .reason,
        )
        assertEquals(
            PairingSubmissionRejection.UNSUPPORTED,
            assertIs<PairingSubmissionOutcome.Rejected>(
                ignoredGateway.submit(valid.replace("\"format_version\":1", "\"format_version\":2")),
            ).reason,
        )
        assertEquals(
            PairingSubmissionRejection.EXPIRED,
            assertIs<PairingSubmissionOutcome.Rejected>(
                createPairingSubmissionGateway(
                    RecordingPairingSession(),
                    Clock { 1_786_550_400 },
                ).submit(valid),
            ).reason,
        )
        assertFalse("seeded-secret" in ignoredGateway.toString())
    }

    private fun buildWire(builder: JsonObject): ByteArray =
        (
            builder.string("prefix") +
                builder.string("repeat_ascii").repeat(builder.int("repeat_count")) +
                builder.string("suffix")
        ).encodeToByteArray()
}

private class RecordingPairingSession(
    private val admission: PairingAdmission = PairingAdmission.ACCEPTED,
) : PairingSessionPort<PairingQr> {
    var admitted: PairingQr? = null

    override fun isConnecting(): Boolean = false

    override fun isUnpaired(): Boolean = true

    override fun admit(material: PairingQr): PairingAdmission {
        admitted = material
        return admission
    }

    override fun toString(): String = "RecordingPairingSession(redacted)"
}

private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

private fun JsonObject.int(name: String): Int = string(name).toInt()

private fun JsonObject.long(name: String): Long = string(name).toLong()

private fun JsonObject.boolean(name: String): Boolean = string(name).toBooleanStrict()
