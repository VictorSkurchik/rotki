package org.rotki.mobile.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.prepareGet
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import org.rotki.mobile.core.protocol.dto.CompanionEnvelopeDecodeOutcome
import org.rotki.mobile.core.protocol.dto.ProtocolDiscoveryEnvelopeDto
import org.rotki.mobile.core.protocol.generated.ProtocolClientInputLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail

class CompanionHttpEnvelopeBoundaryTest {
    @Test
    fun `missing content length accepts exactly 65536 bytes`() = runTest {
        val body = successBodyOfSize(ProtocolClientInputLimits.MaximumControlResponseBytes)

        withStatement(body) { statement ->
            val outcome: CompanionEnvelopeDecodeOutcome<ProtocolDiscoveryEnvelopeDto> =
                statement.executeCompanionSuccessEnvelope(
                    ProtocolDiscoveryEnvelopeDto.serializer(),
                )
            val decoded = when (outcome) {
                is CompanionEnvelopeDecodeOutcome.Decoded -> outcome.value
                CompanionEnvelopeDecodeOutcome.ContractFailure -> fail("Expected decoded response")
            }
            assertEquals(listOf(1), decoded.result.supportedProtocolVersions)
        }
    }

    @Test
    fun `missing content length rejects 65537 bytes while streaming`() = runTest {
        val body = successBodyOfSize(ProtocolClientInputLimits.MaximumControlResponseBytes + 1)

        withStatement(body) { statement ->
            assertIs<CompanionEnvelopeDecodeOutcome.ContractFailure>(
                statement.executeCompanionSuccessEnvelope(
                    ProtocolDiscoveryEnvelopeDto.serializer(),
                ),
            )
        }
    }

    @Test
    fun `declared oversized response is rejected before reading its small body`() = runTest {
        val channel = ByteReadChannel(successBodyOfSize(256).encodeToByteArray())

        assertIs<BoundedControlResponseBodyOutcome.ContractFailure>(
            readBoundedControlResponseText(
                declaredLength = ProtocolClientInputLimits.MaximumControlResponseBytes + 1L,
                channel = channel,
            ),
        )
    }

    @Test
    fun `false small content length cannot bypass streaming cap`() = runTest {
        val body = successBodyOfSize(ProtocolClientInputLimits.MaximumControlResponseBytes + 1)

        assertIs<BoundedControlResponseBodyOutcome.ContractFailure>(
            readBoundedControlResponseText(
                declaredLength = 1L,
                channel = ByteReadChannel(body.encodeToByteArray()),
            ),
        )
    }

    @Test
    fun `accepted body outcome redacts response text`() {
        val outcome = BoundedControlResponseBodyOutcome.Accepted("seeded_access_secret")

        assertEquals("Accepted(redacted)", outcome.toString())
    }

    private suspend fun withStatement(
        body: String,
        block: suspend (io.ktor.client.statement.HttpStatement) -> Unit,
    ): Unit {
        val client = createCompanionHttpClient(MockEngine { respond(body) })
        try {
            block(client.prepareGet("https://rotki.example/api/1/companion/protocol"))
        } finally {
            client.close()
        }
    }
}

private fun successBodyOfSize(size: Int): String {
    val prefix =
        """{"result":{"supported_protocol_versions":[1],"capabilities":{"device_sessions":1}},"message":"","padding":""""
    val suffix = "\"}"
    require(size >= prefix.length + suffix.length)
    return prefix + "x".repeat(size - prefix.length - suffix.length) + suffix
}
