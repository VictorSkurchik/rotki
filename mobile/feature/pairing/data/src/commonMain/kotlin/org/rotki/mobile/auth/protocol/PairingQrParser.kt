@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package org.rotki.mobile.auth.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.rotki.mobile.core.ports.Clock
import org.rotki.mobile.core.protocol.CompanionJsonCodec
import org.rotki.mobile.core.protocol.EngineOrigin
import org.rotki.mobile.core.protocol.EngineOriginParseOutcome
import org.rotki.mobile.core.protocol.PairingCredential
import org.rotki.mobile.core.protocol.PairingId
import org.rotki.mobile.core.protocol.ProtocolValueParseOutcome
import org.rotki.mobile.core.protocol.StrictJsonIntSerializer
import org.rotki.mobile.core.protocol.StrictJsonLongSerializer
import org.rotki.mobile.core.protocol.hasDuplicateJsonMember
import org.rotki.mobile.core.protocol.hasValidJsonSyntax
import org.rotki.mobile.feature.pairing.domain.PairingAdmission
import org.rotki.mobile.feature.pairing.domain.PairingSessionPort
import org.rotki.mobile.feature.pairing.domain.PairingSubmissionGateway
import org.rotki.mobile.feature.pairing.domain.PairingSubmissionOutcome
import org.rotki.mobile.feature.pairing.domain.PairingSubmissionRejection
import kotlin.native.HiddenFromObjC

@HiddenFromObjC
public fun createPairingSubmissionGateway(
    session: PairingSessionPort<PairingQr>,
    clock: Clock,
): PairingSubmissionGateway = PairingSubmissionDataGateway(session, PairingQrParser(clock))

private class PairingSubmissionDataGateway(
    private val session: PairingSessionPort<PairingQr>,
    private val parser: PairingQrParser,
) : PairingSubmissionGateway {
    override fun submit(rawPayload: String): PairingSubmissionOutcome =
        when (val outcome = parser.parse(rawPayload.encodeToByteArray())) {
            is PairingQrParseOutcome.Accepted -> {
                when (session.admit(outcome.pairingQr)) {
                    PairingAdmission.ACCEPTED -> PairingSubmissionOutcome.Accepted
                    PairingAdmission.IGNORED -> PairingSubmissionOutcome.Ignored
                }
            }

            is PairingQrParseOutcome.Rejected -> {
                PairingSubmissionOutcome.Rejected(outcome.reason.toDomainRejection())
            }
        }

    override fun toString(): String = "PairingSubmissionDataGateway(redacted)"
}

internal class PairingQrParser(
    private val clock: Clock,
) {
    internal fun parse(raw: ByteArray): PairingQrParseOutcome {
        if (raw.size > MAX_PAIRING_QR_BYTES) {
            return PairingQrParseOutcome.Rejected(PairingQrRejection.TOO_LARGE)
        }
        val text =
            try {
                raw.decodeToString(throwOnInvalidSequence = true)
            } catch (_: CharacterCodingException) {
                return PairingQrParseOutcome.Rejected(PairingQrRejection.INVALID_UTF8)
            }
        if (hasDuplicateJsonMember(text)) {
            return PairingQrParseOutcome.Rejected(PairingQrRejection.DUPLICATE_MEMBER)
        }
        if (!hasValidJsonSyntax(text)) {
            return PairingQrParseOutcome.Rejected(PairingQrRejection.INVALID_SHAPE)
        }
        val dto =
            try {
                val element = CompanionJsonCodec.parseToJsonElement(text).jsonObject
                if (!element.hasStrictPairingQrTypes()) {
                    return PairingQrParseOutcome.Rejected(PairingQrRejection.INVALID_SHAPE)
                }
                CompanionJsonCodec.decodeFromJsonElement(PairingQrDto.serializer(), element)
            } catch (_: SerializationException) {
                return PairingQrParseOutcome.Rejected(PairingQrRejection.INVALID_SHAPE)
            } catch (_: IllegalArgumentException) {
                return PairingQrParseOutcome.Rejected(PairingQrRejection.INVALID_SHAPE)
            }
        if (dto.kind != PAIRING_KIND || dto.formatVersion != PAIRING_FORMAT_VERSION) {
            return PairingQrParseOutcome.Rejected(PairingQrRejection.UNSUPPORTED_FORMAT)
        }
        val origin =
            when (val parsed = EngineOrigin.parse(dto.engineOrigin)) {
                is EngineOriginParseOutcome.Accepted -> {
                    parsed.origin
                }

                is EngineOriginParseOutcome.Rejected -> {
                    return PairingQrParseOutcome.Rejected(PairingQrRejection.INVALID_ORIGIN)
                }
            }
        val pairingId =
            when (val parsed = PairingId.parse(dto.pairingId)) {
                is ProtocolValueParseOutcome.Accepted -> {
                    parsed.value
                }

                is ProtocolValueParseOutcome.Rejected -> {
                    return PairingQrParseOutcome.Rejected(PairingQrRejection.INVALID_PAIRING_ID)
                }
            }
        val credential =
            when (val parsed = PairingCredential.parse(dto.pairingCredential)) {
                is ProtocolValueParseOutcome.Accepted -> {
                    parsed.value
                }

                is ProtocolValueParseOutcome.Rejected -> {
                    return PairingQrParseOutcome.Rejected(PairingQrRejection.INVALID_CREDENTIAL)
                }
            }
        if (dto.expiresAt <= clock.nowEpochSeconds()) {
            return PairingQrParseOutcome.Rejected(PairingQrRejection.EXPIRED)
        }
        return PairingQrParseOutcome.Accepted(
            PairingQr(origin, pairingId, credential, dto.expiresAt),
        )
    }
}

@HiddenFromObjC
public class PairingQr internal constructor(
    public val engineOrigin: EngineOrigin,
    public val pairingId: PairingId,
    public val pairingCredential: PairingCredential,
    public val expiresAtEpochSeconds: Long,
) {
    public override fun toString(): String = "PairingQr(redacted)"
}

internal sealed interface PairingQrParseOutcome {
    data class Accepted(
        internal val pairingQr: PairingQr,
    ) : PairingQrParseOutcome

    data class Rejected(
        internal val reason: PairingQrRejection,
    ) : PairingQrParseOutcome
}

internal enum class PairingQrRejection {
    TOO_LARGE,
    INVALID_UTF8,
    INVALID_SHAPE,
    DUPLICATE_MEMBER,
    UNSUPPORTED_FORMAT,
    INVALID_ORIGIN,
    INVALID_PAIRING_ID,
    INVALID_CREDENTIAL,
    EXPIRED,
}

private fun PairingQrRejection.toDomainRejection(): PairingSubmissionRejection =
    when (this) {
        PairingQrRejection.EXPIRED -> PairingSubmissionRejection.EXPIRED

        PairingQrRejection.UNSUPPORTED_FORMAT -> PairingSubmissionRejection.UNSUPPORTED

        PairingQrRejection.DUPLICATE_MEMBER,
        PairingQrRejection.INVALID_CREDENTIAL,
        PairingQrRejection.INVALID_ORIGIN,
        PairingQrRejection.INVALID_PAIRING_ID,
        PairingQrRejection.INVALID_SHAPE,
        PairingQrRejection.INVALID_UTF8,
        PairingQrRejection.TOO_LARGE,
        -> PairingSubmissionRejection.MALFORMED
    }

@Serializable
private class PairingQrDto(
    @SerialName("kind")
    val kind: String,
    @SerialName("format_version")
    val formatVersion:
        @Serializable(with = StrictJsonIntSerializer::class)
        Int,
    @SerialName("engine_origin")
    val engineOrigin: String,
    @SerialName("pairing_id")
    val pairingId: String,
    @SerialName("pairing_credential")
    val pairingCredential: String,
    @SerialName("expires_at")
    val expiresAt:
        @Serializable(with = StrictJsonLongSerializer::class)
        Long,
)

private const val MAX_PAIRING_QR_BYTES: Int = 2_048
private const val PAIRING_KIND: String = "rotki_companion_pairing"
private const val PAIRING_FORMAT_VERSION: Int = 1

private fun JsonObject.hasStrictPairingQrTypes(): Boolean =
    PAIRING_QR_STRING_MEMBERS.all { name ->
        (this[name] as? JsonPrimitive)?.isString == true
    } &&
        PAIRING_QR_INTEGER_MEMBERS.all { name ->
            val primitive = this[name] as? JsonPrimitive
            primitive != null && !primitive.isString && JSON_INTEGER.matches(primitive.content)
        }

private val PAIRING_QR_STRING_MEMBERS: Set<String> =
    setOf(
        "kind",
        "engine_origin",
        "pairing_id",
        "pairing_credential",
    )
private val PAIRING_QR_INTEGER_MEMBERS: Set<String> = setOf("format_version", "expires_at")
private val JSON_INTEGER: Regex = Regex("-?(?:0|[1-9][0-9]*)")
