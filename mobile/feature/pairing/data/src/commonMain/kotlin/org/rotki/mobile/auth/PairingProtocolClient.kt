@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package org.rotki.mobile.auth

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import org.rotki.mobile.auth.protocol.DeviceLabel
import org.rotki.mobile.auth.protocol.DeviceSession
import org.rotki.mobile.auth.protocol.DeviceSessionEnvelopeDto
import org.rotki.mobile.auth.protocol.PairingQr
import org.rotki.mobile.auth.protocol.RegisterDeviceSessionRequestDto
import org.rotki.mobile.auth.protocol.matchesRegistration
import org.rotki.mobile.auth.protocol.toDomainOrNull
import org.rotki.mobile.core.network.CompanionHttpResponseOutcome
import org.rotki.mobile.core.network.createPlatformCompanionHttpClient
import org.rotki.mobile.core.network.executeCompanionResponse
import org.rotki.mobile.core.protocol.CompanionFailure
import org.rotki.mobile.core.protocol.CompanionJsonCodec
import org.rotki.mobile.core.protocol.DeviceSessionId
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
import kotlin.native.HiddenFromObjC
import org.rotki.mobile.core.protocol.toDomain as toCompanionFailure

@HiddenFromObjC
public interface PairingRegistrationRemoteGateway {
    public suspend fun discover(origin: EngineOrigin): PairingDiscoveryOutcome

    /**
     * Uses [pairingQr], [idempotencyKey], and [deviceLabel] only for this registration operation.
     * Implementations must not retain, persist, log, or expose those values through exceptions or
     * object representations.
     */
    public suspend fun register(
        pairingQr: PairingQr,
        selectedProtocolVersion: Int,
        idempotencyKey: IdempotencyKey,
        deviceLabel: DeviceLabel,
        platform: CompanionPlatform,
        publicKey: X963PublicKey,
    ): PairingRegistrationRemoteOutcome

    public fun close(): Unit
}

@HiddenFromObjC
public fun createPlatformPairingRegistrationRemoteGateway(): PairingRegistrationRemoteGateway =
    PairingProtocolClient(createPlatformCompanionHttpClient())

internal class PairingProtocolClient(
    private val client: HttpClient,
) : PairingRegistrationRemoteGateway {
    override suspend fun discover(origin: EngineOrigin): PairingDiscoveryOutcome {
        val response =
            client
                .prepareRequest(
                    "${origin.restApiBase}/companion${PairingProtocolRoutes.Discovery.path}",
                ) {
                    method = PairingProtocolRoutes.Discovery.method
                    header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                }.executeCompanionResponse(
                    expectedSuccessStatusCode = PairingProtocolRoutes.Discovery.successStatusCode,
                    deserializer = ProtocolDiscoveryEnvelopeDto.serializer(),
                )
        return when (response) {
            is CompanionHttpResponseOutcome.Success -> {
                when (
                    val negotiation =
                        response.value.negotiate(
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
                    val failure = response.envelope.toCompanionFailure(response.statusCode)
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

    override suspend fun register(
        pairingQr: PairingQr,
        selectedProtocolVersion: Int,
        idempotencyKey: IdempotencyKey,
        deviceLabel: DeviceLabel,
        platform: CompanionPlatform,
        publicKey: X963PublicKey,
    ): PairingRegistrationRemoteOutcome =
        register(
            PairingRegistrationRequest(
                engineOrigin = pairingQr.engineOrigin,
                pairingId = pairingQr.pairingId,
                pairingCredential = pairingQr.pairingCredential,
                selectedProtocolVersion = selectedProtocolVersion,
                idempotencyKey = idempotencyKey,
                deviceLabel = deviceLabel,
                platform = platform,
                publicKey = publicKey,
            ),
        )

    internal suspend fun register(request: PairingRegistrationRequest): PairingRegistrationRemoteOutcome {
        val response =
            client
                .prepareRequest(
                    "${request.engineOrigin.restApiBase}/companion${PairingProtocolRoutes.Registration.path}",
                ) {
                    method = PairingProtocolRoutes.Registration.method
                    header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                    header(ProtocolHeaders.Protocol, request.selectedProtocolVersion.toString())
                    header(HttpHeaders.Authorization, "Bearer ${request.pairingCredential.encoded}")
                    header(ProtocolHeaders.IdempotencyKey, request.idempotencyKey.encoded)
                    setBody(
                        CompanionJsonContent.create(
                            RegisterDeviceSessionRequestDto
                                .create(
                                    pairingId = request.pairingId,
                                    deviceLabel = request.deviceLabel,
                                    platform = request.platform,
                                    publicKey = request.publicKey,
                                ).encodeCompanionJson(),
                        ),
                    )
                }.executeCompanionResponse(
                    expectedSuccessStatusCode = PairingProtocolRoutes.Registration.successStatusCode,
                    deserializer = DeviceSessionEnvelopeDto.serializer(),
                    requiredSuccessCacheControl = "no-store",
                )
        return when (response) {
            is CompanionHttpResponseOutcome.Success -> {
                val deviceSession =
                    response.value.result.deviceSession
                        .toDomainOrNull()
                if (deviceSession != null &&
                    deviceSession.matchesRegistration(request.deviceLabel, request.platform)
                ) {
                    PairingRegistrationRemoteOutcome.Registered(
                        PairingRegisteredSession(
                            id = deviceSession.id,
                            pairedAtEpochSeconds = deviceSession.pairedAtEpochSeconds,
                        ),
                    )
                } else {
                    PairingRegistrationRemoteOutcome.ContractFailure(
                        PairingProtocolRoutes.Registration.successStatusCode,
                    )
                }
            }

            is CompanionHttpResponseOutcome.Failure -> {
                PairingRegistrationRemoteOutcome.Rejected(
                    statusCode = response.statusCode,
                    failure = response.envelope.toCompanionFailure(response.statusCode),
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

    override fun close(): Unit = client.close()
}

private fun RegisterDeviceSessionRequestDto.encodeCompanionJson(): ByteArray =
    CompanionJsonCodec.encodeToByteArray(RegisterDeviceSessionRequestDto.serializer(), this)

private class CompanionJsonContent private constructor(
    private val content: ByteArray,
) : OutgoingContent.ByteArrayContent() {
    override val contentType: ContentType = ContentType.Application.Json
    override val contentLength: Long = content.size.toLong()

    override fun bytes(): ByteArray = content.copyOf()

    override fun toString(): String = "CompanionJsonContent(redacted)"

    companion object {
        internal fun create(bytes: ByteArray): CompanionJsonContent =
            try {
                CompanionJsonContent(bytes.copyOf())
            } finally {
                bytes.fill(0)
            }
    }
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

@HiddenFromObjC
public sealed interface PairingDiscoveryOutcome {
    @HiddenFromObjC
    public data class Compatible(
        public val selectedProtocolVersion: Int,
    ) : PairingDiscoveryOutcome {
        public override fun toString(): String = "Compatible(redacted)"
    }

    @HiddenFromObjC
    public data object Incompatible : PairingDiscoveryOutcome

    @HiddenFromObjC
    public data class Rejected(
        public val failure: CompanionFailure,
        public val retryAfterSeconds: Long?,
    ) : PairingDiscoveryOutcome {
        public override fun toString(): String = "Rejected(redacted)"
    }

    @HiddenFromObjC
    public data class ContractFailure(
        public val statusCode: Int,
    ) : PairingDiscoveryOutcome {
        public override fun toString(): String = "ContractFailure(redacted)"
    }

    @HiddenFromObjC
    public data object PreResponseTransportFailure : PairingDiscoveryOutcome

    @HiddenFromObjC
    public data object CompleteResponseTransportFailure : PairingDiscoveryOutcome
}

@HiddenFromObjC
public sealed interface PairingRegistrationRemoteOutcome {
    @HiddenFromObjC
    public data class Registered(
        public val deviceSession: PairingRegisteredSession,
    ) : PairingRegistrationRemoteOutcome {
        public override fun toString(): String = "Registered(redacted)"
    }

    @HiddenFromObjC
    public data class Rejected(
        public val statusCode: Int,
        public val failure: CompanionFailure,
        public val retryAfterSeconds: Long?,
    ) : PairingRegistrationRemoteOutcome {
        public override fun toString(): String = "Rejected(redacted)"
    }

    @HiddenFromObjC
    public data class ContractFailure(
        public val statusCode: Int,
    ) : PairingRegistrationRemoteOutcome {
        public override fun toString(): String = "ContractFailure(redacted)"
    }

    @HiddenFromObjC
    public data object PreResponseTransportFailure : PairingRegistrationRemoteOutcome

    @HiddenFromObjC
    public data object CompleteResponseTransportFailure : PairingRegistrationRemoteOutcome
}

@HiddenFromObjC
public data class PairingRegisteredSession(
    public val id: DeviceSessionId,
    public val pairedAtEpochSeconds: Long,
) {
    public override fun toString(): String = "PairingRegisteredSession(redacted)"
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
