package org.rotki.mobile.core.state

public sealed interface CompanionRootState {
    public val code: String

    public data object Unpaired : CompanionRootState {
        override val code: String = "unpaired"
    }

    public data object DeviceLocked : CompanionRootState {
        override val code: String = "device_locked"
    }

    public data object Connecting : CompanionRootState {
        override val code: String = "connecting"
    }

    public data object Online : CompanionRootState {
        override val code: String = "online"
    }

    public data object Refreshing : CompanionRootState {
        override val code: String = "refreshing"
    }

    public data object Degraded : CompanionRootState {
        override val code: String = "degraded"
    }

    public data object Unreachable : CompanionRootState {
        override val code: String = "unreachable"
    }

    public data object EngineLocked : CompanionRootState {
        override val code: String = "engine_locked"
    }

    public data object ProfileMismatch : CompanionRootState {
        override val code: String = "profile_mismatch"
    }

    public data object Incompatible : CompanionRootState {
        override val code: String = "incompatible"
    }

    public data object Revoked : CompanionRootState {
        override val code: String = "revoked"
    }

    public companion object {
        internal val entries: List<CompanionRootState> =
            listOf(
                Unpaired,
                DeviceLocked,
                Connecting,
                Online,
                Refreshing,
                Degraded,
                Unreachable,
                EngineLocked,
                ProfileMismatch,
                Incompatible,
                Revoked,
            )
    }
}
