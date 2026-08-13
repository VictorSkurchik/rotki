package org.rotki.mobile.core.protocol.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.rotki.mobile.core.protocol.generated.ProtocolCapability
import org.rotki.mobile.core.protocol.StrictJsonIntSerializer

@Serializable
internal class ProtocolDiscoveryResultDto(
    @SerialName("supported_protocol_versions")
    internal val supportedProtocolVersions:
        List<@Serializable(with = StrictJsonIntSerializer::class) Int>,
    @SerialName("capabilities")
    internal val capabilities: Map<String, @Serializable(with = StrictJsonIntSerializer::class) Int>,
)

internal sealed interface ProtocolNegotiationOutcome {
    data class Compatible(
        internal val selectedVersion: Int,
        internal val availableCapabilities: Set<ProtocolCapability>,
    ) : ProtocolNegotiationOutcome

    data object Incompatible : ProtocolNegotiationOutcome

    data object ContractFailure : ProtocolNegotiationOutcome
}

internal fun ProtocolDiscoveryResultDto.negotiate(
    supportedClientVersions: Set<Int>,
): ProtocolNegotiationOutcome {
    if (supportedProtocolVersions.any { version -> version <= 0 } ||
        capabilities.values.any { version -> version <= 0 }
    ) {
        return ProtocolNegotiationOutcome.ContractFailure
    }
    val selectedVersion = supportedProtocolVersions
        .asSequence()
        .filter { version -> version in supportedClientVersions }
        .maxOrNull()
        ?: return ProtocolNegotiationOutcome.Incompatible
    val available = ProtocolCapability.entries
        .filterTo(mutableSetOf()) { capability ->
            val advertised = capabilities[capability.wireValue]
            advertised != null && advertised >= capability.minimumVersion
        }
    return if (ProtocolCapability.DeviceSessions in available) {
        ProtocolNegotiationOutcome.Compatible(selectedVersion, available)
    } else {
        ProtocolNegotiationOutcome.Incompatible
    }
}
