package org.rotki.mobile.feature.pairing.domain

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PairingSessionPortsTest {
    @Test
    fun `admission vocabulary is closed and secret free`() {
        assertEquals(
            setOf(PairingAdmission.ACCEPTED, PairingAdmission.IGNORED),
            PairingAdmission.entries.toSet(),
        )

        val representations = PairingAdmission.entries.joinToString()
        SECRET_MARKERS.forEach { marker ->
            assertFalse(marker in representations, marker)
        }
    }

    @Test
    fun `session port admits material without exposing it in its representation`() {
        val port = RecordingSessionPort()

        assertTrue(port.isUnpaired())
        assertFalse(port.isConnecting())
        assertEquals(PairingAdmission.ACCEPTED, port.admit(SecretMaterial()))
        assertEquals(1, port.admissionCount)
        assertEquals("RecordingSessionPort(redacted)", port.toString())
        SECRET_MARKERS.forEach { marker ->
            assertFalse(marker in port.toString(), marker)
        }
    }

    @Test
    fun `attempt port passes only opaque session scoped capabilities`() {
        val port = RecordingAttemptPort()
        val attempt = requireNotNull(port.takePending())

        assertSame(port.attempt, attempt)
        assertTrue(port.isCurrent(attempt))
        assertEquals("owned", runSynchronously { port.withConnectionOwnership { "owned" } })
        runSynchronously { port.awaitLoss(attempt) }

        val cleanup = requireNotNull(port.markCleanupRequired(attempt))
        assertSame(port.cleanup, cleanup)
        assertTrue(port.hasPendingCleanup())
        assertTrue(port.markDurable(attempt))
        assertTrue(port.commit(attempt))
        assertTrue(port.abort(attempt))
        assertTrue(port.completeCleanup(cleanup))
        assertTrue(port.abandonCleanup(cleanup))
        assertSame(cleanup, port.claimRecoveredCleanup())
        assertEquals(1, port.ownershipCount)
        assertEquals(1, port.lossWaitCount)

        listOf(port, attempt, cleanup).forEach { value ->
            SECRET_MARKERS.forEach { marker ->
                assertFalse(marker in value.toString(), marker)
            }
        }
    }
}

private class SecretMaterial {
    override fun toString(): String = SECRET_PAYLOAD
}

private class OpaqueAttempt {
    override fun toString(): String = "OpaqueAttempt(redacted)"
}

private class OpaqueCleanup {
    override fun toString(): String = "OpaqueCleanup(redacted)"
}

private class RecordingSessionPort : PairingSessionPort<SecretMaterial> {
    var admissionCount: Int = 0
        private set

    override fun isConnecting(): Boolean = false

    override fun isUnpaired(): Boolean = true

    override fun admit(material: SecretMaterial): PairingAdmission {
        admissionCount += 1
        return PairingAdmission.ACCEPTED
    }

    override fun toString(): String = "RecordingSessionPort(redacted)"
}

private class RecordingAttemptPort : PairingAttemptPort<OpaqueAttempt, OpaqueCleanup> {
    val attempt: OpaqueAttempt = OpaqueAttempt()
    val cleanup: OpaqueCleanup = OpaqueCleanup()
    var ownershipCount: Int = 0
        private set
    var lossWaitCount: Int = 0
        private set

    override suspend fun <T> withConnectionOwnership(operation: suspend () -> T): T {
        ownershipCount += 1
        return operation()
    }

    override fun hasPendingCleanup(): Boolean = true

    override fun takePending(): OpaqueAttempt = attempt

    override fun isCurrent(attempt: OpaqueAttempt): Boolean = attempt === this.attempt

    override suspend fun awaitLoss(attempt: OpaqueAttempt) {
        if (isCurrent(attempt)) lossWaitCount += 1
    }

    override fun markCleanupRequired(attempt: OpaqueAttempt): OpaqueCleanup? = cleanup.takeIf { isCurrent(attempt) }

    override fun markDurable(attempt: OpaqueAttempt): Boolean = isCurrent(attempt)

    override fun commit(attempt: OpaqueAttempt): Boolean = isCurrent(attempt)

    override fun abort(attempt: OpaqueAttempt): Boolean = isCurrent(attempt)

    override fun completeCleanup(cleanup: OpaqueCleanup): Boolean = cleanup === this.cleanup

    override fun abandonCleanup(cleanup: OpaqueCleanup): Boolean = cleanup === this.cleanup

    override fun claimRecoveredCleanup(): OpaqueCleanup = cleanup

    override fun toString(): String = "RecordingAttemptPort(redacted)"
}

private fun <T> runSynchronously(operation: suspend () -> T): T {
    var completed: Result<T>? = null
    operation.startCoroutine(
        object : Continuation<T> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<T>) {
                completed = result
            }
        },
    )
    return requireNotNull(completed).getOrThrow()
}

private const val SECRET_PAYLOAD: String =
    "rotki_companion_pairing:https://rotki.example:pairing-id:pairing-credential"
private val SECRET_MARKERS: List<String> =
    listOf(
        "rotki.example",
        "pairing-id",
        "pairing-credential",
    )
