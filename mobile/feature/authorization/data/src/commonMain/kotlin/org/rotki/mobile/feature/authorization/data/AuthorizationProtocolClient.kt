@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package org.rotki.mobile.feature.authorization.data

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import kotlinx.serialization.SerializationStrategy
import org.rotki.mobile.auth.protocol.AccessSessionEnvelopeDto
import org.rotki.mobile.auth.protocol.AccessSessionRequestDto
import org.rotki.mobile.auth.protocol.AuthContractOutcome
import org.rotki.mobile.auth.protocol.ChallengeEnvelopeDto
import org.rotki.mobile.auth.protocol.ChallengeRequestDto
import org.rotki.mobile.auth.protocol.toDomain
import org.rotki.mobile.core.network.CompanionHttpResponseOutcome
import org.rotki.mobile.core.network.createPlatformCompanionHttpClient
import org.rotki.mobile.core.network.executeCompanionResponse
import org.rotki.mobile.core.protocol.ChallengeId
import org.rotki.mobile.core.protocol.CompanionFailure
import org.rotki.mobile.core.protocol.CompanionJsonCodec
import org.rotki.mobile.core.protocol.DeviceSessionId
import org.rotki.mobile.core.protocol.EngineOrigin
import org.rotki.mobile.core.protocol.P1363Signature
import org.rotki.mobile.core.protocol.generated.HttpErrorCode
import org.rotki.mobile.core.protocol.generated.ProtocolHeaders
import org.rotki.mobile.feature.authorization.domain.AccessSession
import org.rotki.mobile.feature.authorization.domain.AuthorizationChallenge
import org.rotki.mobile.feature.authorization.domain.AuthorizationRemoteFailure
import org.rotki.mobile.feature.authorization.domain.AuthorizationRemoteGateway
import org.rotki.mobile.feature.authorization.domain.AuthorizationRemoteOutcome
import kotlin.native.HiddenFromObjC
import org.rotki.mobile.core.protocol.toDomain as toCompanionFailure

@HiddenFromObjC
public fun createPlatformAuthorizationRemoteGateway(): AuthorizationRemoteGateway =
    AuthorizationProtocolClient(createPlatformCompanionHttpClient())

internal class AuthorizationProtocolClient(
    private val client: HttpClient,
) : AuthorizationRemoteGateway {
    override suspend fun requestChallenge(
        engineOrigin: EngineOrigin,
        deviceSessionId: DeviceSessionId,
        selectedProtocolVersion: Int,
    ): AuthorizationRemoteOutcome<AuthorizationChallenge> {
        require(selectedProtocolVersion > 0) { "Selected protocol version must be positive" }
        val response =
            client
                .prepareRequest(
                    "${engineOrigin.restApiBase}/companion${AuthorizationProtocolRoutes.Challenge.path}",
                ) {
                    method = AuthorizationProtocolRoutes.Challenge.method
                    header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                    header(ProtocolHeaders.Protocol, selectedProtocolVersion.toString())
                    setBody(
                        AuthorizationJsonContent.create(
                            ChallengeRequestDto.create(deviceSessionId).encodeCompanionJson(
                                ChallengeRequestDto.serializer(),
                            ),
                        ),
                    )
                }.executeCompanionResponse(
                    expectedSuccessStatusCode = AuthorizationProtocolRoutes.Challenge.successStatusCode,
                    deserializer = ChallengeEnvelopeDto.serializer(),
                    requiredSuccessCacheControl = NO_STORE,
                )
        return response.mapSuccess { envelope -> envelope.result.toDomain() }
    }

    override suspend fun submitProof(
        engineOrigin: EngineOrigin,
        deviceSessionId: DeviceSessionId,
        challengeId: ChallengeId,
        signature: P1363Signature,
        selectedProtocolVersion: Int,
    ): AuthorizationRemoteOutcome<AccessSession> {
        require(selectedProtocolVersion > 0) { "Selected protocol version must be positive" }
        val response =
            client
                .prepareRequest(
                    "${engineOrigin.restApiBase}/companion${AuthorizationProtocolRoutes.AccessSession.path}",
                ) {
                    method = AuthorizationProtocolRoutes.AccessSession.method
                    header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                    header(ProtocolHeaders.Protocol, selectedProtocolVersion.toString())
                    setBody(
                        AuthorizationJsonContent.create(
                            AccessSessionRequestDto
                                .create(deviceSessionId, challengeId, signature)
                                .encodeCompanionJson(AccessSessionRequestDto.serializer()),
                        ),
                    )
                }.executeCompanionResponse(
                    expectedSuccessStatusCode = AuthorizationProtocolRoutes.AccessSession.successStatusCode,
                    deserializer = AccessSessionEnvelopeDto.serializer(),
                    requiredSuccessCacheControl = NO_STORE,
                )
        return response.mapSuccess { envelope -> envelope.result.toDomain() }
    }

    override fun close(): Unit = client.close()

    override fun toString(): String = "AuthorizationProtocolClient(redacted)"
}

private inline fun <Wire, Domain> CompanionHttpResponseOutcome<Wire>.mapSuccess(
    mapping: (Wire) -> AuthContractOutcome<Domain>,
): AuthorizationRemoteOutcome<Domain> =
    when (this) {
        is CompanionHttpResponseOutcome.Success -> {
            when (val mapped = mapping(value)) {
                is AuthContractOutcome.Accepted -> AuthorizationRemoteOutcome.Success(mapped.value)
                AuthContractOutcome.ContractFailure -> AuthorizationRemoteOutcome.ContractFailure
            }
        }

        is CompanionHttpResponseOutcome.Failure -> {
            val failure = envelope.toCompanionFailure(statusCode).toAuthorizationRemoteFailure()
            AuthorizationRemoteOutcome.Rejected(
                failure = failure,
                retryAfterSeconds = retryAfterSeconds,
            )
        }

        is CompanionHttpResponseOutcome.ContractFailure -> {
            AuthorizationRemoteOutcome.ContractFailure
        }

        CompanionHttpResponseOutcome.PreResponseTransportFailure -> {
            AuthorizationRemoteOutcome.PreResponseTransportFailure
        }

        CompanionHttpResponseOutcome.CompleteResponseTransportFailure -> {
            AuthorizationRemoteOutcome.CompleteResponseTransportFailure
        }
    }

private fun CompanionFailure.toAuthorizationRemoteFailure(): AuthorizationRemoteFailure =
    when (this) {
        is CompanionFailure.Known -> {
            when (code) {
                HttpErrorCode.InvalidRequest -> AuthorizationRemoteFailure.INVALID_REQUEST
                HttpErrorCode.NotAuthorized -> AuthorizationRemoteFailure.NOT_AUTHORIZED
                HttpErrorCode.ProfileMismatch -> AuthorizationRemoteFailure.PROFILE_MISMATCH
                HttpErrorCode.ChallengeUnavailable -> AuthorizationRemoteFailure.CHALLENGE_UNAVAILABLE
                HttpErrorCode.LockedEngine -> AuthorizationRemoteFailure.LOCKED_ENGINE
                HttpErrorCode.IncompatibleProtocol -> AuthorizationRemoteFailure.INCOMPATIBLE_PROTOCOL
                HttpErrorCode.RateLimited -> AuthorizationRemoteFailure.RATE_LIMITED
                HttpErrorCode.UnexpectedEngineError -> AuthorizationRemoteFailure.UNEXPECTED_ENGINE_ERROR
                else -> AuthorizationRemoteFailure.UNEXPECTED_ENGINE_ERROR
            }
        }

        CompanionFailure.UnexpectedEngineError -> {
            AuthorizationRemoteFailure.UNEXPECTED_ENGINE_ERROR
        }
    }

private fun <T> T.encodeCompanionJson(serializer: SerializationStrategy<T>): ByteArray =
    CompanionJsonCodec.encodeToByteArray(serializer, this)

private class AuthorizationJsonContent private constructor(
    private val content: ByteArray,
) : OutgoingContent.ByteArrayContent() {
    override val contentType: ContentType = ContentType.Application.Json
    override val contentLength: Long = content.size.toLong()

    override fun bytes(): ByteArray = content.copyOf()

    override fun toString(): String = "AuthorizationJsonContent(redacted)"

    companion object {
        fun create(bytes: ByteArray): AuthorizationJsonContent =
            try {
                AuthorizationJsonContent(bytes.copyOf())
            } finally {
                bytes.fill(0)
            }
    }
}

internal class AuthorizationProtocolRoute(
    internal val id: String,
    internal val method: HttpMethod,
    internal val path: String,
    internal val successStatusCode: Int,
)

internal object AuthorizationProtocolRoutes {
    internal val Challenge: AuthorizationProtocolRoute =
        AuthorizationProtocolRoute(
            id = "create_challenge",
            method = HttpMethod.Post,
            path = "/challenges",
            successStatusCode = 201,
        )
    internal val AccessSession: AuthorizationProtocolRoute =
        AuthorizationProtocolRoute(
            id = "create_access_session",
            method = HttpMethod.Post,
            path = "/access-sessions",
            successStatusCode = 201,
        )
}

private const val NO_STORE: String = "no-store"
