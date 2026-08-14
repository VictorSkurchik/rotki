package org.rotki.mobile.feature.pairing.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PairingSubmissionTest {
    @Test
    fun `rejection vocabulary is closed and coarse`() {
        assertEquals(
            setOf(
                PairingSubmissionRejection.MALFORMED,
                PairingSubmissionRejection.UNSUPPORTED,
                PairingSubmissionRejection.EXPIRED,
            ),
            PairingSubmissionRejection.entries.toSet(),
        )
    }

    @Test
    fun `outcomes cannot represent submitted authority`() {
        val outcomes =
            listOf(
                PairingSubmissionOutcome.Accepted,
                PairingSubmissionOutcome.Ignored,
                *PairingSubmissionRejection.entries
                    .map(PairingSubmissionOutcome::Rejected)
                    .toTypedArray(),
            )
        val representations = outcomes.joinToString()

        SECRET_MARKERS.forEach { marker ->
            assertFalse(marker in representations, marker)
        }
    }

    @Test
    fun `gateway returns only the typed outcome of a one-shot submission`() {
        var submissionCount = 0
        val gateway =
            PairingSubmissionGateway {
                submissionCount += 1
                PairingSubmissionOutcome.Rejected(PairingSubmissionRejection.EXPIRED)
            }

        assertEquals(
            PairingSubmissionOutcome.Rejected(PairingSubmissionRejection.EXPIRED),
            gateway.submit(SECRET_PAYLOAD),
        )
        assertEquals(1, submissionCount)
    }
}

private const val SECRET_PAYLOAD: String =
    "rotki_companion_pairing:https://rotki.example:pairing-id:pairing-credential"
private val SECRET_MARKERS: List<String> =
    listOf(
        "rotki.example",
        "pairing-id",
        "pairing-credential",
    )
