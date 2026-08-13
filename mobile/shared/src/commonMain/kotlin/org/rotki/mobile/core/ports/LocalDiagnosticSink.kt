package org.rotki.mobile.core.ports

public enum class DiagnosticEventCode {
    CONTRACT_REJECTED,
    NETWORK_REQUEST_FAILED,
    STORAGE_FAILED,
    UNEXPECTED_ENGINE_FAILURE,
    WEBSOCKET_EVENT_REJECTED,
}

public fun interface LocalDiagnosticSink {
    public fun record(eventCode: DiagnosticEventCode): Unit
}
