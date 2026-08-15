package org.rotki.mobile.auth.protocol

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.rotki.mobile.core.protocol.CompanionJsonCodec
import org.rotki.mobile.core.protocol.testing.ProtocolFixtureData
import org.rotki.mobile.feature.authorization.data.TestAuthorizationValues
import org.rotki.mobile.feature.authorization.domain.AccessSession
import org.rotki.mobile.feature.authorization.domain.AuthorizationChallenge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class AuthorizationDtoContractTest {
    @Test
    fun `challenge and access fixtures preserve exact request bytes and response mapping`() {
        val challenge = fixture("create_challenge")
        val access = fixture("create_access_session")

        assertEquals(
            challenge
                .getValue("request")
                .jsonObject
                .getValue("body")
                .toString(),
            encode(
                ChallengeRequestDto.create(
                    TestAuthorizationValues.deviceSessionId(),
                ),
                ChallengeRequestDto.serializer(),
            ),
        )
        assertEquals(
            access
                .getValue("request")
                .jsonObject
                .getValue("body")
                .toString(),
            encode(
                AccessSessionRequestDto.create(
                    deviceSessionId = TestAuthorizationValues.deviceSessionId(),
                    challengeId = TestAuthorizationValues.challengeId(),
                    signature = TestAuthorizationValues.signature(),
                ),
                AccessSessionRequestDto.serializer(),
            ),
        )
        val decodedChallenge = decodeResponse(challenge, ChallengeEnvelopeDto.serializer())
        val mappedChallenge =
            assertIs<AuthContractOutcome.Accepted<AuthorizationChallenge>>(
                decodedChallenge.result.toDomain(),
            )
        val expectedChallenge =
            challenge
                .getValue("response")
                .jsonObject
                .getValue("result")
                .jsonObject
        assertEquals(
            expectedChallenge.getValue("challenge_id").jsonPrimitive.content,
            mappedChallenge.value.id.encoded,
        )
        assertEquals(
            expectedChallenge.getValue("nonce").jsonPrimitive.content,
            decodedChallenge.result.nonce,
        )
        assertEquals(
            TestAuthorizationValues.nonce().bytesCopy().toList(),
            mappedChallenge.value.nonce
                .bytesCopy()
                .toList(),
        )
        assertEquals(
            expectedChallenge.getValue("expires_at").jsonPrimitive.long,
            mappedChallenge.value.expiresAtEpochSeconds,
        )

        val decodedAccess = decodeResponse(access, AccessSessionEnvelopeDto.serializer())
        val mappedAccess =
            assertIs<AuthContractOutcome.Accepted<AccessSession>>(
                decodedAccess.result.toDomain(),
            )
        val expectedAccess =
            access
                .getValue("response")
                .jsonObject
                .getValue("result")
                .jsonObject
        assertEquals(
            expectedAccess.getValue("access_session_credential").jsonPrimitive.content,
            decodedAccess.result.accessSessionCredential,
        )
        assertEquals(
            expectedAccess.getValue("expires_at").jsonPrimitive.long,
            mappedAccess.value.expiresAtEpochSeconds,
        )
    }

    @Test
    fun `strict scalar and fixed width values fail closed`() {
        assertFailsWith<SerializationException> {
            ProtocolFixtureData.decodeCompanionJson(
                responseText("create_challenge")
                    .replace("\"expires_at\":1786550400", "\"expires_at\":\"1786550400\""),
                ChallengeEnvelopeDto.serializer(),
            )
        }
        assertFailsWith<SerializationException> {
            ProtocolFixtureData.decodeCompanionJson(
                responseText("create_access_session")
                    .replace("\"expires_at\":1786551300", "\"expires_at\":\"1786551300\""),
                AccessSessionEnvelopeDto.serializer(),
            )
        }

        val malformedChallenge =
            decodeResponse(
                responseText("create_challenge").replace(
                    TestAuthorizationValues.CHALLENGE_ID,
                    "padded=",
                ),
                ChallengeEnvelopeDto.serializer(),
            )
        assertEquals(AuthContractOutcome.ContractFailure, malformedChallenge.result.toDomain())

        val malformedAccess =
            decodeResponse(
                responseText("create_access_session").replace(
                    TestAuthorizationValues.ACCESS_CREDENTIAL,
                    "not_base64url+",
                ),
                AccessSessionEnvelopeDto.serializer(),
            )
        assertEquals(AuthContractOutcome.ContractFailure, malformedAccess.result.toDomain())

        assertEquals(
            AuthContractOutcome.ContractFailure,
            ChallengeResultDto(
                challengeId = TestAuthorizationValues.CHALLENGE_ID,
                nonce = TestAuthorizationValues.NONCE,
                expiresAt = -1,
            ).toDomain(),
        )
        assertEquals(
            AuthContractOutcome.ContractFailure,
            AccessSessionResultDto(
                accessSessionCredential = TestAuthorizationValues.ACCESS_CREDENTIAL,
                expiresAt = -1,
            ).toDomain(),
        )
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `serializer identities and field order stay byte compatible with shared`() {
        assertDescriptor(
            ChallengeRequestDto.serializer().descriptor,
            "org.rotki.mobile.auth.protocol.ChallengeRequestDto",
            listOf("device_session_id"),
        )
        assertDescriptor(
            ChallengeResultDto.serializer().descriptor,
            "org.rotki.mobile.auth.protocol.ChallengeResultDto",
            listOf("challenge_id", "nonce", "expires_at"),
        )
        assertDescriptor(
            AccessSessionRequestDto.serializer().descriptor,
            "org.rotki.mobile.auth.protocol.AccessSessionRequestDto",
            listOf("device_session_id", "challenge_id", "signature"),
        )
        assertDescriptor(
            AccessSessionResultDto.serializer().descriptor,
            "org.rotki.mobile.auth.protocol.AccessSessionResultDto",
            listOf("access_session_credential", "expires_at"),
        )
        assertDescriptor(
            ChallengeEnvelopeDto.serializer().descriptor,
            "org.rotki.mobile.core.protocol.dto.ChallengeEnvelopeDto",
            listOf("result", "message"),
        )
        assertDescriptor(
            AccessSessionEnvelopeDto.serializer().descriptor,
            "org.rotki.mobile.core.protocol.dto.AccessSessionEnvelopeDto",
            listOf("result", "message"),
        )
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun assertDescriptor(
        descriptor: SerialDescriptor,
        expectedSerialName: String,
        expectedElements: List<String>,
    ) {
        assertEquals(expectedSerialName, descriptor.serialName)
        assertEquals(
            expectedElements,
            (0 until descriptor.elementsCount).map(descriptor::getElementName),
        )
    }

    private fun fixture(id: String): JsonObject = ProtocolFixtureData.successExample(id)

    private fun responseText(id: String): String = fixture(id).getValue("response").toString()

    private fun <T> decodeResponse(
        fixture: JsonObject,
        deserializer: DeserializationStrategy<T>,
    ): T = decodeResponse(fixture.getValue("response").toString(), deserializer)

    private fun <T> decodeResponse(
        text: String,
        deserializer: DeserializationStrategy<T>,
    ): T = ProtocolFixtureData.decodeCompanionJson(text, deserializer)

    private fun <T> encode(
        value: T,
        serializer: SerializationStrategy<T>,
    ): String = CompanionJsonCodec.encodeToByteArray(serializer, value).decodeToString()
}
