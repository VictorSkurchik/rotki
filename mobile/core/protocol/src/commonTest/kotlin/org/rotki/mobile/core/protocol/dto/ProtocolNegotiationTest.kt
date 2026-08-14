package org.rotki.mobile.core.protocol.dto

import org.rotki.mobile.core.protocol.generated.ProtocolCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProtocolNegotiationTest {
    @Test
    fun `greatest shared version and known advertised capabilities are selected`() {
        val outcome =
            negotiate(
                supportedProtocolVersions = listOf(3, 1, 2),
                capabilities =
                    mapOf(
                        "device_sessions" to 1,
                        "portfolio_snapshot" to 2,
                        "future_capability" to 99,
                    ),
                supportedClientVersions = setOf(1, 2),
            )

        val compatible = assertIs<ProtocolNegotiationOutcome.Compatible>(outcome)
        assertEquals(2, compatible.selectedVersion)
        assertTrue(ProtocolCapability.DeviceSessions in compatible.availableCapabilities)
        assertTrue(ProtocolCapability.PortfolioSnapshot in compatible.availableCapabilities)
    }

    @Test
    fun `missing shared version or device-session capability is incompatible`() {
        assertIs<ProtocolNegotiationOutcome.Incompatible>(
            negotiate(
                supportedProtocolVersions = listOf(2),
                capabilities = mapOf("device_sessions" to 1),
            ),
        )
        assertIs<ProtocolNegotiationOutcome.Incompatible>(
            negotiate(
                supportedProtocolVersions = listOf(1),
                capabilities = mapOf("future" to 1),
            ),
        )
        assertIs<ProtocolNegotiationOutcome.Incompatible>(
            negotiate(
                supportedProtocolVersions = emptyList(),
                capabilities = mapOf("device_sessions" to 1),
            ),
        )
    }

    @Test
    fun `non-positive protocol or capability versions fail the contract`() {
        assertIs<ProtocolNegotiationOutcome.ContractFailure>(
            negotiate(
                supportedProtocolVersions = listOf(0, 1),
                capabilities = mapOf("device_sessions" to 1),
            ),
        )
        assertIs<ProtocolNegotiationOutcome.ContractFailure>(
            negotiate(
                supportedProtocolVersions = listOf(1),
                capabilities = mapOf("device_sessions" to 0),
            ),
        )
        assertIs<ProtocolNegotiationOutcome.ContractFailure>(
            negotiate(
                supportedProtocolVersions = listOf(1),
                capabilities = mapOf("future" to -1),
            ),
        )
    }

    private fun negotiate(
        supportedProtocolVersions: List<Int>,
        capabilities: Map<String, Int>,
        supportedClientVersions: Set<Int> = setOf(1),
    ): ProtocolNegotiationOutcome =
        ProtocolDiscoveryEnvelopeDto(
            result =
                ProtocolDiscoveryResultDto(
                    supportedProtocolVersions = supportedProtocolVersions,
                    capabilities = capabilities,
                ),
            message = "",
        ).negotiate(supportedClientVersions)
}
