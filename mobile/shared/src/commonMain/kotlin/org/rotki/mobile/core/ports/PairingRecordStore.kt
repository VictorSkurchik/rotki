package org.rotki.mobile.core.ports

import org.rotki.mobile.core.protocol.DeviceSessionId
import org.rotki.mobile.core.protocol.EngineOrigin

public data class PairingRecord(
    public val engineOrigin: EngineOrigin,
    public val deviceSessionId: DeviceSessionId,
) {
    public override fun toString(): String = "PairingRecord(redacted)"
}

public sealed interface PairingRecordReadOutcome {
    public data object Missing : PairingRecordReadOutcome

    public data class Present(public val record: PairingRecord) : PairingRecordReadOutcome

    public data object Corrupt : PairingRecordReadOutcome

    public data object Unavailable : PairingRecordReadOutcome
}

public sealed interface PairingRecordWriteOutcome {
    public data object Stored : PairingRecordWriteOutcome

    public data object Unavailable : PairingRecordWriteOutcome
}

public sealed interface PairingRecordDeleteOutcome {
    public data object Deleted : PairingRecordDeleteOutcome

    public data object Unavailable : PairingRecordDeleteOutcome
}

public interface PairingRecordStore {
    public suspend fun read(): PairingRecordReadOutcome

    public suspend fun write(record: PairingRecord): PairingRecordWriteOutcome

    public suspend fun delete(): PairingRecordDeleteOutcome
}
