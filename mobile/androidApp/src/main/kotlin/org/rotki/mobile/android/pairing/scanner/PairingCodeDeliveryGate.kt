package org.rotki.mobile.android.pairing.scanner

import java.util.concurrent.atomic.AtomicBoolean

internal class PairingCodeDeliveryGate {
    private val lock = Any()
    private var generation: Long = 0L
    private var active: Boolean = false
    private var delivered: Boolean = false

    fun activate(): Unit =
        synchronized(lock) {
            generation += 1L
            active = true
        }

    fun deactivate(): Unit =
        synchronized(lock) {
            generation += 1L
            active = false
        }

    fun currentSession(): Long? =
        synchronized(lock) {
            generation.takeIf { active }
        }

    fun offer(
        session: Long,
        payload: String?,
        deliver: (String) -> Unit,
    ) {
        if (payload.isNullOrBlank()) return
        if (claim(session)) {
            deliver(payload)
        }
    }

    fun fail(
        session: Long,
        deliver: () -> Unit,
    ) {
        if (claim(session)) deliver()
    }

    fun restart(): Unit =
        synchronized(lock) {
            generation += 1L
            delivered = false
        }

    private fun claim(session: Long): Boolean =
        synchronized(lock) {
            if (!active || session != generation || delivered) {
                false
            } else {
                delivered = true
                true
            }
        }
}

internal class CloseOnce(
    private val closeAction: () -> Unit,
) {
    private val closed = AtomicBoolean(false)

    fun close() {
        if (closed.compareAndSet(false, true)) {
            closeAction()
        }
    }
}
