package org.rotki.mobile.core.state

public sealed interface SnapshotCoverage {
    public val code: String

    public data object Absent : SnapshotCoverage {
        override val code: String = "absent"
    }

    public data object Complete : SnapshotCoverage {
        override val code: String = "complete"
    }

    public data object Degraded : SnapshotCoverage {
        override val code: String = "degraded"
    }
}

public data class CompanionStatus(
    public val rootState: CompanionRootState,
    public val snapshotCoverage: SnapshotCoverage,
)
