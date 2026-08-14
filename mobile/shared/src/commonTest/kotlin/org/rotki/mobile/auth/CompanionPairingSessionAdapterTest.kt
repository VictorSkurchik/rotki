package org.rotki.mobile.auth

import org.rotki.mobile.CompanionFacade
import org.rotki.mobile.PairingCleanupHandle
import org.rotki.mobile.auth.protocol.PairingQr
import org.rotki.mobile.core.state.CompanionRootState
import org.rotki.mobile.core.state.CompanionTransitionOutcome
import org.rotki.mobile.feature.pairing.domain.PairingAdmission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CompanionPairingSessionAdapterTest {
    @Test
    fun `pending Pairing material has a one shot handoff`() {
        val facade = CompanionFacade()
        val adapter = CompanionPairingSessionAdapter(facade)
        val material = parsedQr(FIRST_EXPIRY)

        assertEquals(PairingAdmission.ACCEPTED, adapter.admit(material))

        val lease = assertNotNull(adapter.takePending())
        assertSame(material, lease.pairingQr)
        assertTrue(adapter.isCurrent(lease))
        assertNull(adapter.takePending())
        assertEquals(CompanionRootState.Connecting, facade.status.value.rootState)
    }

    @Test
    fun `stale attempt and cleanup capabilities cannot mutate a replacement`() {
        val facade = CompanionFacade()
        val adapter = CompanionPairingSessionAdapter(facade)
        assertEquals(PairingAdmission.ACCEPTED, adapter.admit(parsedQr(FIRST_EXPIRY)))
        val staleAttempt = assertNotNull(adapter.takePending())
        val staleCleanup = assertNotNull(adapter.markCleanupRequired(staleAttempt))
        assertTrue(adapter.completeCleanup(staleCleanup))

        assertEquals(PairingAdmission.ACCEPTED, adapter.admit(parsedQr(SECOND_EXPIRY)))
        val replacement = assertNotNull(adapter.takePending())

        assertFalse(adapter.isCurrent(staleAttempt))
        assertNull(adapter.markCleanupRequired(staleAttempt))
        assertFalse(adapter.markDurable(staleAttempt))
        assertFalse(adapter.commit(staleAttempt))
        assertFalse(adapter.abort(staleAttempt))
        assertFalse(adapter.completeCleanup(staleCleanup))
        assertFalse(adapter.abandonCleanup(staleCleanup))
        assertTrue(adapter.isCurrent(replacement))
        assertEquals(SECOND_EXPIRY, replacement.pairingQr.expiresAtEpochSeconds)
        assertEquals(CompanionRootState.Connecting, facade.status.value.rootState)
    }

    @Test
    fun `recovered cleanup barrier rejects racing and fresh admissions until completion`() {
        val facade = CompanionFacade()
        val adapter = CompanionPairingSessionAdapter(facade)
        val recoveredCleanup = assertNotNull(adapter.claimRecoveredCleanup())

        val racingAdmission = facade.acceptPairing(parsedQr(FIRST_EXPIRY))

        assertIs<CompanionTransitionOutcome.Rejected>(racingAdmission)
        assertTrue(adapter.hasPendingCleanup())
        assertNull(adapter.takePending())
        assertEquals(PairingAdmission.IGNORED, adapter.admit(parsedQr(SECOND_EXPIRY)))
        assertNull(adapter.takePending())
        assertEquals(CompanionRootState.Unpaired, facade.status.value.rootState)

        assertTrue(adapter.completeCleanup(recoveredCleanup))
        assertFalse(adapter.hasPendingCleanup())
        assertEquals(PairingAdmission.ACCEPTED, adapter.admit(parsedQr(SECOND_EXPIRY)))
        val replacement = assertNotNull(adapter.takePending())
        assertEquals(SECOND_EXPIRY, replacement.pairingQr.expiresAtEpochSeconds)
    }

    @Test
    fun `pending cleanup claimed before final ownership check rejects admission`() {
        val facade = CompanionFacade()
        val adapter = CompanionPairingSessionAdapter(facade)
        var cleanup: PairingCleanupHandle? = null

        val admission =
            facade.acceptPairing(
                pairingQr = parsedQr(FIRST_EXPIRY),
                afterPendingStored = {},
                beforeFinalOwnershipCheck = {
                    val lease = assertNotNull(adapter.takePending())
                    cleanup = adapter.markCleanupRequired(lease)
                },
            )

        assertIs<CompanionTransitionOutcome.Rejected>(admission)
        assertTrue(adapter.hasPendingCleanup())
        assertNull(adapter.takePending())
        assertEquals(CompanionRootState.Unpaired, facade.status.value.rootState)
        assertTrue(adapter.abandonCleanup(assertNotNull(cleanup)))
        assertFalse(adapter.hasPendingCleanup())
    }

    @Test
    fun `late recovered barrier claim cannot revoke an accepted attempt`() {
        val facade = CompanionFacade()
        val adapter = CompanionPairingSessionAdapter(facade)
        var admission: CompanionTransitionOutcome? = null

        val cleanup =
            facade.claimRecoveredPairingCleanup {
                admission = facade.acceptPairing(parsedQr(FIRST_EXPIRY))
            }

        assertIs<CompanionTransitionOutcome.Applied>(admission)
        assertNull(cleanup)
        assertFalse(adapter.hasPendingCleanup())
        assertEquals(CompanionRootState.Connecting, facade.status.value.rootState)
        assertNotNull(adapter.takePending())
    }

    @Test
    fun `adapter capabilities and secret material stay redacted`() {
        val facade = CompanionFacade()
        val adapter = CompanionPairingSessionAdapter(facade)
        val material = parsedQr(FIRST_EXPIRY)
        assertEquals(PairingAdmission.ACCEPTED, adapter.admit(material))
        val lease = assertNotNull(adapter.takePending())
        val cleanup = assertNotNull(adapter.markCleanupRequired(lease))

        val representations =
            listOf(
                adapter,
                material,
                lease,
                cleanup,
                PairingAdmission.ACCEPTED,
            ).joinToString()

        SECRET_MARKERS.forEach { marker ->
            assertFalse(marker in representations, marker)
        }
        assertEquals("CompanionPairingSessionAdapter(redacted)", adapter.toString())
        assertEquals("PairingQr(redacted)", material.toString())
        assertEquals("PendingPairingLease(redacted)", lease.toString())
        assertEquals("PairingCleanupHandle(redacted)", cleanup.toString())
    }
}

private fun parsedQr(expiresAt: Long): PairingQr = acceptedPairingQr(validQr(expiresAt), NOW)

private fun validQr(expiresAt: Long): String =
    """{"kind":"rotki_companion_pairing","format_version":1,"engine_origin":"$ORIGIN","pairing_id":"$PAIRING_ID","pairing_credential":"$PAIRING_CREDENTIAL","expires_at":$expiresAt}"""

private const val NOW: Long = 1_786_550_300L
private const val FIRST_EXPIRY: Long = NOW + 100L
private const val SECOND_EXPIRY: Long = NOW + 200L
private const val ORIGIN: String = "https://rotki.example"
private const val PAIRING_ID: String = "AAECAwQFBgcICQoLDA0ODw"
private const val PAIRING_CREDENTIAL: String =
    "EBESExQVFhcYGRobHB0eHyAhIiMkJSYnKCkqKywtLi8"
private val SECRET_MARKERS: List<String> =
    listOf(
        ORIGIN,
        PAIRING_ID,
        PAIRING_CREDENTIAL,
        "rotki_companion_pairing",
    )
