package org.rotki.mobile.core.network

import kotlinx.coroutines.sync.Mutex

internal class SessionRenewalGate {
    private val mutex: Mutex = Mutex()

    internal fun tryAcquire(): SessionRenewalLease? {
        val owner = Any()
        return if (mutex.tryLock(owner)) {
            SessionRenewalLease(mutex, owner)
        } else {
            null
        }
    }
}

internal class SessionRenewalLease(
    private val mutex: Mutex,
    private val owner: Any,
) {
    internal fun release(): Unit = mutex.unlock(owner)
}
