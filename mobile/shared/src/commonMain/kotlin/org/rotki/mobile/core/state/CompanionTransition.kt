package org.rotki.mobile.core.state

internal enum class CompanionTransitionEvent(
    internal val code: String,
) {
    START_WITHOUT_PAIRING("start_without_pairing"),
    ACCEPT_PAIRING_QR("accept_pairing_qr"),
    BACKGROUND_OR_SYSTEM_LOCK("background_or_system_lock"),
    DEVICE_AUTHENTICATION_SUCCEEDED("device_authentication_succeeded"),
    PROOF_AND_COMPLETE_SNAPSHOT_RECONCILED("proof_and_complete_snapshot_reconciled"),
    PROOF_AND_DEGRADED_SNAPSHOT_RECONCILED("proof_and_degraded_snapshot_reconciled"),
    ACTIVE_REFRESH_RECONCILED("active_refresh_reconciled"),
    PROOF_AND_ACTIVE_REFRESH_RECONCILED("proof_and_active_refresh_reconciled"),
    TERMINAL_REFRESH_AND_COMPLETE_SNAPSHOT("terminal_refresh_and_complete_snapshot"),
    TERMINAL_REFRESH_AND_DEGRADED_SNAPSHOT("terminal_refresh_and_degraded_snapshot"),
    TRANSPORT_RETRY_BUDGET_EXHAUSTED("transport_retry_budget_exhausted"),
    TRANSPORT_RESTORED("transport_restored"),
    ACCESS_SESSION_UNAVAILABLE("access_session_unavailable"),
    PROACTIVE_RENEWAL_STARTED("proactive_renewal_started"),
    WEBSOCKET_CLOSE_1008("websocket_close_1008"),
    LOCKED_ENGINE("locked_engine"),
    PROFILE_MISMATCH("profile_mismatch"),
    NO_SHARED_PROTOCOL_OR_DEVICE_SESSIONS_CAPABILITY(
        "no_shared_protocol_or_device_sessions_capability",
    ),
    NOT_AUTHORIZED("not_authorized"),
    CHALLENGE_UNAVAILABLE("challenge_unavailable"),
    EXPLICIT_FOREGROUND_RETRY("explicit_foreground_retry"),
    LOCAL_UNPAIR("local_unpair"),
    ;

    internal companion object {
        internal fun fromCode(code: String): CompanionTransitionEvent? =
            entries.firstOrNull { event -> event.code == code }
    }
}

public enum class DeviceSessionEffect(
    public val code: String,
) {
    ABSENT("absent"),
    DELETE("delete"),
    KEEP("keep"),
    REPLACE("replace"),
}

public enum class SnapshotEffect(
    public val code: String,
) {
    ABSENT("absent"),
    DELETE("delete"),
    KEEP("keep"),
    KEEP_ENCRYPTED("keep_encrypted"),
    REPLACE("replace"),
    UNLOCK("unlock"),
}

public enum class BearerEffect(
    public val code: String,
) {
    ABSENT("absent"),
    DELETE("delete"),
    KEEP("keep"),
    KEEP_UNTIL_EXPIRY("keep_until_expiry"),
    REPLACE("replace"),
}

public data class CompanionTransitionEffects(
    public val deviceSession: DeviceSessionEffect,
    public val snapshot: SnapshotEffect,
    public val bearer: BearerEffect,
)

public sealed interface CompanionTransitionOutcome {
    public val status: CompanionStatus

    public data class Applied(
        override val status: CompanionStatus,
        public val effects: CompanionTransitionEffects,
    ) : CompanionTransitionOutcome

    public data class Rejected(
        override val status: CompanionStatus,
        public val eventCode: String,
    ) : CompanionTransitionOutcome
}
