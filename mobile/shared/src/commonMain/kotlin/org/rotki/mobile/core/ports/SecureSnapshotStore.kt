package org.rotki.mobile.core.ports

public sealed interface SecureSnapshotReadOutcome {
    public data object Missing : SecureSnapshotReadOutcome

    public class Unlocked(document: ByteArray) : SecureSnapshotReadOutcome {
        private val storedDocument: ByteArray = document.copyOf()

        public fun documentCopy(): ByteArray = storedDocument.copyOf()

        override fun equals(other: Any?): Boolean =
            other is Unlocked && storedDocument.contentEquals(other.storedDocument)

        override fun hashCode(): Int = storedDocument.contentHashCode()

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
