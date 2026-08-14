package org.rotki.mobile.auth

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import org.rotki.mobile.auth.protocol.AuthContractOutcome
import org.rotki.mobile.auth.protocol.DeviceLabel
import org.rotki.mobile.auth.protocol.DeviceSession
import org.rotki.mobile.auth.protocol.DeviceSessionEnvelopeDto
import org.rotki.mobile.auth.protocol.RegisterDeviceSessionRequestDto
import org.rotki.mobile.auth.protocol.matchesRegistration
import org.rotki.mobile.core.network.CompanionHttpResponseOutcome
import org.rotki.mobile.core.network.executeCompanionResponse
import org.rotki.mobile.core.protocol.CompanionFailure
import org.rotki.mobile.core.protocol.EngineOrigin
import org.rotki.mobile.core.protocol.IdempotencyKey
import org.rotki.mobile.core.protocol.PairingCredential
import org.rotki.mobile.core.protocol.PairingId
import org.rotki.mobile.core.protocol.X963PublicKey
import org.rotki.mobile.core.protocol.dto.ProtocolDiscoveryEnvelopeDto
import org.rotki.mobile.core.protocol.dto.ProtocolNegotiationOutcome
import org.rotki.mobile.core.protocol.dto.negotiate
import org.rotki.mobile.core.protocol.generated.CompanionPlatform
import org.rotki.mobile.core.protocol.generated.HttpErrorCode
import org.rotki.mobile.core.protocol.generated.ProtocolHeaders
import org.rotki.mobile.core.protocol.generated.SUPPORTED_PROTOCOL_VERSIONS
import org.rotki.mobile.auth.protocol.toDomain as toDeviceSession
import org.rotki.mobile.core.protocol.toDomain as toCompanionFailure

internal class PairingProtocolClient(
    private val client: HttpClient,
) {
    internal suspend fun discover(origin: EngineOrigin): PairingDiscoveryOutcome {
        val response =
            client
                .prepareRequest(
                    "${origin.restApiBase}/companion${PairingProtocolRoutes.Discovery.path}",
                ) {
                    method = PairingProtocolRoutes.Discovery.method
                }.executeCompanionResponse(
                    expectedSuccessStatusCode = PairingProtocolRoutes.Discovery.successStatusCode,
                    deserializer = ProtocolDiscoveryEnvelopeDto.serializer(),
                )
        return when (response) {
            is CompanionHttpResponseOutcome.Success -> {
                when (
                    val negotiation =
                        response.value.result.negotiate(
                            SUPPORTED_PROTOCOL_VERSIONS,
                        )
                ) {
                    is ProtocolNegotiationOutcome.Compatible -> {
                        PairingDiscoveryOutcome.Compatible(negotiation.selectedVersion)
                    }

                    ProtocolNegotiationOutcome.Incompatible -> {
                        PairingDiscoveryOutcome.Incompatible
                    }

                    ProtocolNegotiationOutcome.ContractFailure -> {
                        PairingDiscoveryOutcome.ContractFailure(
                            PairingProtocolRoutes.Discovery.successStatusCode,
                        )
                    }
                }
            }

            is CompanionHttpResponseOutcome.Failure -> {
                if (response.statusCode == HTTP_NOT_FOUND) {
                    PairingDiscoveryOutcome.Incompatible
                } else {
                    val failure = response.envelope.error.toCompanionFailure(response.statusCode)
                    if (failure.isIncompatibleProtocol()) {
                        PairingDiscoveryOutcome.Incompatible
                    } else {
                        PairingDiscoveryOutcome.Rejected(
                            failure = failure,
                            retryAfterSeconds = response.retryAfterSeconds,
                        )
                    }
                }
            }

            is CompanionHttpResponseOutcome.ContractFailure -> {
                if (response.statusCode == HTTP_NOT_FOUND) {
                    PairingDiscoveryOutcome.Incompatible
                } else {
                    PairingDiscoveryOutcome.ContractFailure(response.statusCode)
                }
            }

            CompanionHttpResponseOutcome.PreResponseTransportFailure -> {
                PairingDiscoveryOutcome.PreResponseTransportFailure
            }

            CompanionHttpResponseOutcome.CompleteResponseTransportFailure -> {
                PairingDiscoveryOutcome.CompleteResponseTransportFailure
            }
        }
    }

    internal suspend fun register(request: PairingRegistrationRequest): PairingRegistrationRemoteOutcome {
        val response =
            client
                .prepareRequest(
                    "${request.engineOrigin.restApiBase}/companion${PairingProtocolRoutes.Registration.path}",
                ) {
                    method = PairingProtocolRoutes.Registration.method
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    header(ProtocolHeaders.Protocol, request.selectedProtocolVersion.toString())
                    header(HttpHeaders.Authorization, "Bearer ${request.pairingCredential.encoded}")
                    header(ProtocolHeaders.IdempotencyKey, request.idempotencyKey.encoded)
                    setBody(
                        RegisterDeviceSessionRequestDto.create(
                            pairingId = request.pairingId,
                            deviceLabel = request.deviceLabel,
                            platform = request.platform,
                            publicKey = request.publicKey,
                        ),
                    )
                }.executeCompanionResponse(
                    expectedSuccessStatusCode = PairingProtocolRoutes.Registration.successStatusCode,
                    deserializer = DeviceSessionEnvelopeDto.serializer(),
                    requiredSuccessCacheControl = "no-store",
                )
        return when (response) {
            is CompanionHttpResponseOutcome.Success -> {
                when (
                    val domain =
                        response.value.result.deviceSession
                            .toDeviceSession()
                ) {
                    is AuthContractOutcome.Accepted -> {
                        if (
                            domain.value.matchesRegistration(request.deviceLabel, request.platform)
                        ) {
                            PairingRegistrationRemoteOutcome.Registered(domain.value)
                        } else {
                            PairingRegistrationRemoteOutcome.ContractFailure(
                                PairingProtocolRoutes.Registration.successStatusCode,
                            )
                        }
                    }

                    AuthContractOutcome.ContractFailure -> {
                        PairingRegistrationRemoteOutcome.ContractFailure(
                            PairingProtocolRoutes.Registration.successStatusCode,
                        )
                    }
                }
            }

            is CompanionHttpResponseOutcome.Failure -> {
                PairingRegistrationRemoteOutcome.Rejected(
                    statusCode = response.statusCode,
                    failure = response.envelope.error.toCompanionFailure(response.statusCode),
                    retryAfterSeconds = response.retryAfterSeconds,
                )
            }

            is CompanionHttpResponseOutcome.ContractFailure -> {
                PairingRegistrationRemoteOutcome.ContractFailure(response.statusCode)
            }

            CompanionHttpResponseOutcome.PreResponseTransportFailure -> {
                PairingRegistrationRemoteOutcome.PreResponseTransportFailure
            }

            CompanionHttpResponseOutcome.CompleteResponseTransportFailure -> {
                PairingRegistrationRemoteOutcome.CompleteResponseTransportFailure
            }
        }
    }

    internal fun close(): Unit = client.close()
}

internal class PairingRegistrationRequest(
    internal val engineOrigin: EngineOrigin,
    internal val pairingId: PairingId,
    internal val pairingCredential: PairingCredential,
    internal val selectedProtocolVersion: Int,
    internal val idempotencyKey: IdempotencyKey,
    internal val deviceLabel: DeviceLabel,
    internal val platform: CompanionPlatform,
    internal val publicKey: X963PublicKey,
) {
    override fun toString(): String = "PairingRegistrationRequest(redacted)"
}

internal sealed interface PairingDiscoveryOutcome {
    data class Compatible(
        internal val selectedProtocolVersion: Int,
    ) : PairingDiscoveryOutcome

    data object Incompatible : PairingDiscoveryOutcome

    data class Rejected(
        internal val failure: CompanionFailure,
        internal val retryAfterSeconds: Long?,
    ) : PairingDiscoveryOutcome

    data class ContractFailure(
        internal val statusCode: Int,
    ) : PairingDiscoveryOutcome

    data object PreResponseTransportFailure : PairingDiscoveryOutcome

    data object CompleteResponseTransportFailure : PairingDiscoveryOutcome
}

internal sealed interface PairingRegistrationRemoteOutcome {
    data class Registered(
        internal val deviceSession: DeviceSession,
    ) : PairingRegistrationRemoteOutcome

    data class Rejected(
        internal val statusCode: Int,
        internal val failure: CompanionFailure,
        internal val retryAfterSeconds: Long?,
    ) : PairingRegistrationRemoteOutcome

    data class ContractFailure(
        internal val statusCode: Int,
    ) : PairingRegistrationRemoteOutcome

    data object PreResponseTransportFailure : PairingRegistrationRemoteOutcome

    data object CompleteResponseTransportFailure : PairingRegistrationRemoteOutcome
}

private fun CompanionFailure.isIncompatibleProtocol(): Boolean =
    this is CompanionFailure.Known && code == HttpErrorCode.IncompatibleProtocol

private const val HTTP_NOT_FOUND: Int = 404

internal class PairingProtocolRoute(
    internal val id: String,
    internal val method: HttpMethod,
    internal val path: String,
    internal val successStatusCode: Int,
)

internal object PairingProtocolRoutes {
    internal val Discovery: PairingProtocolRoute =
        PairingProtocolRoute(
            id = "get_protocol",
            method = HttpMethod.Get,
            path = "/protocol",
            successStatusCode = 200,
        )

    internal val Registration: PairingProtocolRoute =
        PairingProtocolRoute(
            id = "register_device_session",
            method = HttpMethod.Post,
            path = "/device-sessions",
            successStatusCode = 201,
        )
}
