package org.rotki.mobile.core.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.rotki.mobile.core.protocol.dto.CompanionEnvelopeDecodeOutcome
import org.rotki.mobile.core.protocol.dto.CompanionEnvelopeDecoder
import org.rotki.mobile.core.protocol.testing.ProtocolFixtureData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail

class CompanionErrorMapperFixtureTest {
    @Test
    fun `all twenty authored error rows map with their exact public tuple`() {
        val cases = ProtocolFixtureData.cases.getValue("error_cases").jsonArray
        assertEquals(20, cases.size)
        cases.forEach { element ->
            val case = element.jsonObject
            val envelope =
                when (
                    val outcome =
                        CompanionEnvelopeDecoder.decodeFailure(
                            case.failureEnvelope(),
                        )
                ) {
                    is CompanionEnvelopeDecodeOutcome.Decoded -> outcome.value
                    CompanionEnvelopeDecodeOutcome.ContractFailure -> fail(case.string("code"))
                }
            val failure =
                assertIs<CompanionFailure.Known>(
                    envelope.toDomain(case.int("status")),
                    case.string("code"),
                )
            assertEquals(case.string("code"), failure.code.wireValue)
            assertEquals(case.string("action"), failure.action.wireValue)
            assertEquals(case.boolean("retryable"), failure.retryable)
            assertEquals(case.int("status"), failure.statusCode)
            assertEquals(case.localEffect(), failure.localEffect)
        }
    }
}

private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

private fun JsonObject.int(name: String): Int = string(name).toInt()

private fun JsonObject.boolean(name: String): Boolean = string(name).toBooleanStrict()

private fun JsonObject.localEffect(): CompanionFailureLocalEffect =
    when (val effect = string("device_session_effect")) {
        "keep" -> CompanionFailureLocalEffect.KEEP
        "delete" -> CompanionFailureLocalEffect.DELETE_PAIRING_AND_SNAPSHOT
        "not_established" -> CompanionFailureLocalEffect.NOT_ESTABLISHED
        "unchanged" -> CompanionFailureLocalEffect.UNCHANGED
        else -> fail("Unknown device_session_effect: $effect")
    }

private fun JsonObject.failureEnvelope(): String {
    val code = string("code")
    val retryable = boolean("retryable")
    val action = string("action")
    return """{
  "result": null,
  "message": "redacted",
  "error": {
    "code": "$code",
    "retryable": $retryable,
    "action": "$action"
  }
}"""
}
