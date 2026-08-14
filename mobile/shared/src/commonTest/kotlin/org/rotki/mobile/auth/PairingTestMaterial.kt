package org.rotki.mobile.auth

import org.rotki.mobile.auth.protocol.PairingQr
import org.rotki.mobile.auth.protocol.createPairingSubmissionGateway
import org.rotki.mobile.core.ports.Clock
import org.rotki.mobile.feature.pairing.domain.PairingAdmission
import org.rotki.mobile.feature.pairing.domain.PairingSessionPort
import org.rotki.mobile.feature.pairing.domain.PairingSubmissionOutcome
import kotlin.test.assertIs
import kotlin.test.assertNotNull

internal fun acceptedPairingQr(
    rawPayload: String,
    nowEpochSeconds: Long,
): PairingQr {
    val session = CapturingPairingSession()
    val outcome =
        createPairingSubmissionGateway(
            session = session,
            clock = Clock { nowEpochSeconds },
        ).submit(rawPayload)

    assertIs<PairingSubmissionOutcome.Accepted>(outcome)
    return assertNotNull(session.material)
}

private class CapturingPairingSession : PairingSessionPort<PairingQr> {
    var material: PairingQr? = null

    override fun isConnecting(): Boolean = false

    override fun isUnpaired(): Boolean = true

    override fun admit(material: PairingQr): PairingAdmission {
        this.material = material
        return PairingAdmission.ACCEPTED
    }
}
