package org.rotki.mobile.core.ports

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

public sealed interface SecureSnapshotReadOutcome {
    public data object Missing : SecureSnapshotReadOutcome

    /**
     * A revocable application-owned plaintext handle.
     *
     * The native store retains this handle and calls [discard] on background/system lock. Callers
     * must treat every [documentCopy] as short-lived and clear their own copy through the same
     * lifecycle transition; no JVM or Native API can revoke an arbitrary copy retained by a caller.
     */
    @OptIn(ExperimentalAtomicApi::class)
    public class Unlocked(document: ByteArray) : SecureSnapshotReadOutcome {
        private val storedDocument: AtomicReference<ByteArray?> =
            AtomicReference(document.copyOf())

        /** Returns null after this handle has been revoked. */
        public fun documentCopy(): ByteArray? = storedDocument.load()?.copyOf()

        /** Idempotently revokes this handle and overwrites its owned plaintext buffer. */
        public fun discard(): Unit {
            storedDocument.exchange(null)?.fill(0)
        }

        public val isDiscarded: Boolean
            get() = storedDocument.load() == null

        override fun toString(): String = "Unlocked(redacted)"
    }

    public data object DeviceAuthenticationCancelled : SecureSnapshotReadOutcome

    public data object DeviceAuthenticationUnavailable : SecureSnapshotReadOutcome

    public data object PairingRequired : SecureSnapshotReadOutcome

    public data object Corrupt : SecureSnapshotReadOutcome

    public data object Unavailable : SecureSnapshotReadOutcome
}

public sealed interface SecureSnapshotWriteOutcome {
    public data object Stored : SecureSnapshotWriteOutcome

    public data object DeviceAuthenticationRequired : SecureSnapshotWriteOutcome

    public data object PairingRequired : SecureSnapshotWriteOutcome

    public data object Unavailable : SecureSnapshotWriteOutcome
}

public sealed interface SecureSnapshotDeleteOutcome {
    public data object Deleted : SecureSnapshotDeleteOutcome

    public data object Unavailable : SecureSnapshotDeleteOutcome
}

public interface SecureSnapshotStore {
    public suspend fun readAfterDeviceAuthentication(): SecureSnapshotReadOutcome

    public suspend fun replace(document: ByteArray): SecureSnapshotWriteOutcome

    public suspend fun delete(): SecureSnapshotDeleteOutcome

    public fun discardPlaintext(): Unit
}
