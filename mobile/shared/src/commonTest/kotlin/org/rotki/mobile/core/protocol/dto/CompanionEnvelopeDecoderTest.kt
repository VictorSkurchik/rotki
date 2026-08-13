package org.rotki.mobile.core.protocol.dto

import org.rotki.mobile.core.protocol.generated.ProtocolClientInputLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail

class CompanionEnvelopeDecoderTest {
    @Test
    fun `valid additive success is decoded after envelope preflight`() {
        val outcome: CompanionEnvelopeDecodeOutcome<ProtocolDiscoveryEnvelopeDto> =
            CompanionEnvelopeDecoder.decodeSuccess(
                SUCCESS_WITH_ADDITIVE_FIELDS,
                ProtocolDiscoveryEnvelopeDto.serializer(),
            )
        val decoded = when (outcome) {
            is CompanionEnvelopeDecodeOutcome.Decoded -> outcome.value
            CompanionEnvelopeDecodeOutcome.ContractFailure -> fail("Expected decoded success")
        }

        assertEquals(listOf(1), decoded.result.supportedProtocolVersions)
        assertEquals("", decoded.message)
    }

    @Test
    fun `valid additive failure is decoded after envelope preflight`() {
        val outcome: CompanionEnvelopeDecodeOutcome<CompanionFailureEnvelopeDto> =
            CompanionEnvelopeDecoder.decodeFailure(FAILURE_WITH_ADDITIVE_FIELDS)
        val decoded = when (outcome) {
            is CompanionEnvelopeDecodeOutcome.Decoded -> outcome.value
            CompanionEnvelopeDecodeOutcome.ContractFailure -> fail("Expected decoded failure")
        }

        assertEquals(null, decoded.result)
        assertEquals("invalid_request", decoded.error.code)
        assertEquals(false, decoded.error.retryable)
        assertEquals("none", decoded.error.action)
    }

    @Test
    fun `duplicate members are rejected recursively before typed decoding`() {
        val duplicate = SUCCESS_WITH_ADDITIVE_FIELDS.replace(
            "\"device_sessions\":1",
            "\"device_sessions\":1,\"device_sessions\":1",
        )

        assertSuccessContractFailure(duplicate)
    }

    @Test
    fun `success with any error member is rejected as ambiguous`() {
        val ambiguous = SUCCESS_WITH_ADDITIVE_FIELDS.dropLast(1) +
            ",\"error\":{\"code\":\"invalid_request\",\"retryable\":false," +
            "\"action\":\"none\"}}"
        assertSuccessContractFailure(ambiguous)
        assertIs<CompanionEnvelopeDecodeOutcome.ContractFailure>(
            CompanionEnvelopeDecoder.decodeFailure(ambiguous),
        )
        assertSuccessContractFailure(
            SUCCESS_WITH_ADDITIVE_FIELDS.dropLast(1) + ",\"error\":null}",
        )
    }

    @Test
    fun `missing result is rejected for success and failure`() {
        assertSuccessContractFailure("""{"message":""}""")
        assertIs<CompanionEnvelopeDecodeOutcome.ContractFailure>(
            CompanionEnvelopeDecoder.decodeFailure(
                """{"message":"redacted","error":{"code":"invalid_request","retryable":false,"action":"none"}}""",
            ),
        )
    }

    @Test
    fun `null result is rejected for success and non-null result for failure`() {
        assertSuccessContractFailure("""{"result":null,"message":""}""")
        assertIs<CompanionEnvelopeDecodeOutcome.ContractFailure>(
            CompanionEnvelopeDecoder.decodeFailure(
                """{"result":{},"message":"redacted","error":{"code":"invalid_request","retryable":false,"action":"none"}}""",
            ),
        )
    }

    @Test
    fun `missing or null failure error is rejected`() {
        assertIs<CompanionEnvelopeDecodeOutcome.ContractFailure>(
            CompanionEnvelopeDecoder.decodeFailure("""{"result":null,"message":"redacted"}"""),
        )
        assertIs<CompanionEnvelopeDecodeOutcome.ContractFailure>(
            CompanionEnvelopeDecoder.decodeFailure(
                """{"result":null,"message":"redacted","error":null}""",
            ),
        )
    }

    @Test
    fun `oversized or over-depth raw text is rejected before typed decoding`() {
        val oversized = """{"result":{},"message":"","padding":"""" +
            "x".repeat(ProtocolClientInputLimits.MaximumControlResponseBytes) +
            "\"}"
        val nesting = ProtocolClientInputLimits.MaximumJsonNestingDepth
        val overDepth = """{"result":""" + "[".repeat(nesting) + "0" +
            "]".repeat(nesting) + """, "message":""}"""

        assertSuccessContractFailure(oversized)
        assertSuccessContractFailure(overDepth)
    }

    private fun assertSuccessContractFailure(text: String): Unit {
        assertIs<CompanionEnvelopeDecodeOutcome.ContractFailure>(
            CompanionEnvelopeDecoder.decodeSuccess(
                text,
                ProtocolDiscoveryEnvelopeDto.serializer(),
            ),
        )
    }
}

private const val SUCCESS_WITH_ADDITIVE_FIELDS: String =
    """{"result":{"supported_protocol_versions":[1],"capabilities":{"device_sessions":1},"future_result":true},"message":"","future_envelope":true}"""

private const val FAILURE_WITH_ADDITIVE_FIELDS: String =
    """{"result":null,"message":"redacted","error":{"code":"invalid_request","retryable":false,"action":"none","future_error":true},"future_envelope":true}"""
