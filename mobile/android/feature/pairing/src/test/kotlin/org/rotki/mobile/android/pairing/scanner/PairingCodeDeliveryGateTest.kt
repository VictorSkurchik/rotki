package org.rotki.mobile.android.pairing.scanner

import org.junit.Assert.assertEquals
import org.junit.Test

class PairingCodeDeliveryGateTest {
    @Test
    fun `delivers only the first payload until explicit restart`() {
        val delivered = mutableListOf<String>()
        val gate = PairingScannerTestBridge.createGate()
        gate.activate()
        val firstSession = checkNotNull(gate.currentSession())

        gate.offer(firstSession, "first-payload", delivered::add)
        gate.offer(firstSession, "first-payload", delivered::add)
        gate.offer(firstSession, "different-payload", delivered::add)

        assertEquals(listOf("first-payload"), delivered)

        gate.restart()
        val secondSession = checkNotNull(gate.currentSession())
        gate.offer(secondSession, "different-payload", delivered::add)

        assertEquals(listOf("first-payload", "different-payload"), delivered)
    }

    @Test
    fun `blank values do not consume the delivery cycle`() {
        val delivered = mutableListOf<String>()
        val gate = PairingScannerTestBridge.createGate()
        gate.activate()
        val session = checkNotNull(gate.currentSession())

        gate.offer(session, null, delivered::add)
        gate.offer(session, "", delivered::add)
        gate.offer(session, "   ", delivered::add)
        gate.offer(session, "valid-payload", delivered::add)

        assertEquals(listOf("valid-payload"), delivered)
    }

    @Test
    fun `late results from stopped or replaced sessions are ignored`() {
        val delivered = mutableListOf<String>()
        val gate = PairingScannerTestBridge.createGate()
        gate.activate()
        val stoppedSession = checkNotNull(gate.currentSession())

        gate.deactivate()
        gate.offer(stoppedSession, "late-after-stop", delivered::add)
        gate.activate()
        val activeSession = checkNotNull(gate.currentSession())
        gate.offer(stoppedSession, "late-after-resume", delivered::add)
        gate.offer(activeSession, "current", delivered::add)

        assertEquals(listOf("current"), delivered)
    }

    @Test
    fun `detected result redacts its diagnostic representation`() {
        assertEquals(
            "Detected(redacted)",
            PairingScannerTestBridge.detectedResultDiagnostic("sensitive-payload"),
        )
    }
}
