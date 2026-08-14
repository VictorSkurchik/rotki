package org.rotki.mobile.core.ports

/** Secret-free durable intent proving that Pairing key/record cleanup is still required. */
public sealed interface PairingCleanupJournalReadOutcome {
    public data object Clear : PairingCleanupJournalReadOutcome

    public data object CleanupRequired : PairingCleanupJournalReadOutcome

    public data object Unavailable : PairingCleanupJournalReadOutcome
}

public sealed interface PairingCleanupJournalWriteOutcome {
    public data object Stored : PairingCleanupJournalWriteOutcome

    public data object Unavailable : PairingCleanupJournalWriteOutcome
}

public sealed interface PairingCleanupJournalClearOutcome {
    public data object Cleared : PairingCleanupJournalClearOutcome

    public data object Unavailable : PairingCleanupJournalClearOutcome
}

public interface PairingCleanupJournal {
    public suspend fun read(): PairingCleanupJournalReadOutcome

    /** Must durably commit before any Pairing key or record can be created or deleted. */
    public suspend fun markCleanupRequired(): PairingCleanupJournalWriteOutcome

    /** Idempotent; Cleared means the marker is proven absent after this call. */
    public suspend fun clear(): PairingCleanupJournalClearOutcome
}
