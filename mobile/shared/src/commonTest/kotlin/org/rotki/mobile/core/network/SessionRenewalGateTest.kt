package org.rotki.mobile.core.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SessionRenewalGateTest {
    @Test
    fun simultaneousTriggersAcquireExactlyOneRenewalLease(): Unit = runTest {
        val gate = SessionRenewalGate()
        val start = CompletableDeferred<Unit>()
        val attempts = List(CONCURRENT_TRIGGER_COUNT) {
            async(Dispatchers.Default) {
                start.await()
                gate.tryAcquire()
            }
        }

        start.complete(Unit)
        val leases = attempts.awaitAll()

        assertEquals(1, leases.count { lease -> lease != null })
        val winner = assertNotNull(leases.singleOrNull { lease -> lease != null })
        assertNull(gate.tryAcquire())

        winner.release()
        val next = assertNotNull(gate.tryAcquire())
        next.release()
    }

    private companion object {
        private const val CONCURRENT_TRIGGER_COUNT: Int = 64
    }
}
