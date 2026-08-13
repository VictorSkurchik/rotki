package org.rotki.mobile.core.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.rotki.mobile.core.network.CompanionJson
import org.rotki.mobile.core.protocol.dto.CompanionErrorDto
import org.rotki.mobile.core.protocol.dto.CompanionFailureEnvelopeDto
import org.rotki.mobile.core.protocol.generated.HttpErrorCode
import org.rotki.mobile.core.protocol.testing.ProtocolFixtureData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class CompanionErrorMapperTest {
    @Test
    fun `all twenty authored error rows map with exact tuple and local effect`() {
        val cases = ProtocolFixtureData.cases.getValue("error_cases").jsonArray
        assertEquals(20, cases.size)
        cases.forEach { element ->
            val case = element.jsonObject
            val failure = assertIs<CompanionFailure.Known>(
                CompanionErrorDto(
                    code = case.string("code"),
                    retryable = case.boolean("retryable"),
                    action = case.string("action"),
                ).toDomain(case.int("status")),
                case.string("code"),
            )
            assertEquals(case.string("code"), failure.code.wireValue)
            assertEquals(case.string("action"), failure.action.wireValue)
            assertEquals(case.boolean("retryable"), failure.retryable)
            val expectedEffect = when (case.string("device_session_effect")) {
                "delete" -> CompanionFailureLocalEffect.DELETE_PAIRING_AND_SNAPSHOT
                "not_established" -> CompanionFailureLocalEffect.NOT_ESTABLISHED
                "unchanged" -> CompanionFailureLocalEffect.UNCHANGED
                else -> CompanionFailureLocalEffect.KEEP
            }
            assertEquals(expectedEffect, failure.localEffect)
        }
    }

    @Test
    fun `only exact not-authorized tuple is destructive`() {
        val exact = CompanionErrorDto("not_authorized", false, "pair_again").toDomain(401)
        val wrongAction = CompanionErrorDto("not_authorized", false, "none").toDomain(401)
        val wrongStatus = CompanionErrorDto("not_authorized", false, "pair_again").toDomain(403)

        assertEquals(
            CompanionFailureLocalEffect.DELETE_PAIRING_AND_SNAPSHOT,
            assertIs<CompanionFailure.Known>(exact).localEffect,
        )
        assertIs<CompanionFailure.UnexpectedEngineError>(wrongAction)
        assertIs<CompanionFailure.UnexpectedEngineError>(wrongStatus)
    }

    @Test
    fun `unknown tokens and malformed envelopes fail safely`() {
        assertIs<CompanionFailure.UnexpectedEngineError>(
            CompanionErrorDto("future_code", false, "none").toDomain(500),
        )
        assertIs<CompanionFailure.UnexpectedEngineError>(
            CompanionErrorDto(HttpErrorCode.InvalidRequest.wireValue, false, "future_action")
                .toDomain(400),
        )
        assertFailsWith<SerializationException> {
            CompanionJson.decodeFromString<CompanionFailureEnvelopeDto>(
                """{"result":null,"message":"redacted","error":{"code":"invalid_request","retryable":false}}""",
            )
        }
        assertFailsWith<SerializationException> {
            CompanionJson.decodeFromString<CompanionFailureEnvelopeDto>(
                """{"message":"redacted","error":{"code":"invalid_request","retryable":false,"action":"none"}}""",
            )
        }
        assertFailsWith<SerializationException> {
            CompanionJson.decodeFromString<CompanionFailureEnvelopeDto>(
                """{"result":null,"message":"redacted","error":{"code":"invalid_request","retryable":"false","action":"none"}}""",
            )
        }
    }

    @Test
    fun `wire DTO string representations never expose protocol data`() {
        val dto = CompanionErrorDto("seeded_secret_code", false, "seeded_secret_action")
        val rendered = dto.toString()

        kotlin.test.assertFalse(rendered.contains("seeded_secret"))
    }
}

private fun kotlinx.serialization.json.JsonObject.string(name: String): String =
    getValue(name).jsonPrimitive.content
private fun kotlinx.serialization.json.JsonObject.int(name: String): Int = string(name).toInt()
private fun kotlinx.serialization.json.JsonObject.boolean(name: String): Boolean =
    string(name).toBooleanStrict()
