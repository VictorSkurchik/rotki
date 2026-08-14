package org.rotki.mobile.core.protocol

import kotlinx.serialization.SerializationException
import org.rotki.mobile.core.protocol.dto.CompanionErrorDto
import org.rotki.mobile.core.protocol.dto.CompanionFailureEnvelopeDto
import org.rotki.mobile.core.protocol.generated.HttpErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

class CompanionErrorMapperTest {
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
    fun `each local effect category remains explicit`() {
        val cases =
            listOf(
                ErrorEffectCase(400, "invalid_request", false, "none", CompanionFailureLocalEffect.KEEP),
                ErrorEffectCase(
                    401,
                    "full_client_auth_required",
                    false,
                    "authenticate_full_client",
                    CompanionFailureLocalEffect.UNCHANGED,
                ),
                ErrorEffectCase(
                    401,
                    "not_authorized",
                    false,
                    "pair_again",
                    CompanionFailureLocalEffect.DELETE_PAIRING_AND_SNAPSHOT,
                ),
                ErrorEffectCase(
                    410,
                    "pairing_unavailable",
                    false,
                    "pair_again",
                    CompanionFailureLocalEffect.NOT_ESTABLISHED,
                ),
            )

        cases.forEach { case ->
            val failure =
                assertIs<CompanionFailure.Known>(
                    CompanionErrorDto(case.code, case.retryable, case.action).toDomain(case.statusCode),
                )
            assertEquals(case.effect, failure.localEffect)
        }
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
            decodeFailureEnvelope(
                """{"result":null,"message":"redacted","error":{"code":"invalid_request","retryable":false}}""",
            )
        }
        assertFailsWith<SerializationException> {
            decodeFailureEnvelope(
                """{"message":"redacted","error":{"code":"invalid_request","retryable":false,"action":"none"}}""",
            )
        }
        assertFailsWith<SerializationException> {
            decodeFailureEnvelope(
                """{"result":null,"message":"redacted","error":{"code":"invalid_request","retryable":"false","action":"none"}}""",
            )
        }
    }

    @Test
    fun `wire DTO string representations never expose protocol data`() {
        val dto = CompanionErrorDto("seeded_secret_code", false, "seeded_secret_action")
        val rendered = dto.toString()

        assertFalse(rendered.contains("seeded_secret"))
    }
}

private data class ErrorEffectCase(
    val statusCode: Int,
    val code: String,
    val retryable: Boolean,
    val action: String,
    val effect: CompanionFailureLocalEffect,
)

private fun decodeFailureEnvelope(text: String): CompanionFailureEnvelopeDto =
    CompanionJsonCodec.decodeFromJsonElement(
        CompanionFailureEnvelopeDto.serializer(),
        CompanionJsonCodec.parseToJsonElement(text),
    )
