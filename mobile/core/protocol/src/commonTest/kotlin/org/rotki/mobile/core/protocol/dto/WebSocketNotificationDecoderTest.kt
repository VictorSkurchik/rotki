package org.rotki.mobile.core.protocol.dto

import org.rotki.mobile.core.protocol.generated.ProtocolErrorAction
import org.rotki.mobile.core.protocol.generated.SourceErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class WebSocketNotificationDecoderTest {
    @Test
    fun `unknown event is ignored and malformed known events fail closed`() {
        assertIs<WebSocketNotificationDecodeOutcome.IgnoredUnknownEvent>(
            WebSocketNotificationDecoder.decode("""{"type":"future_event","data":{"x":1}}"""),
        )
        assertIs<WebSocketNotificationDecodeOutcome.IgnoredUnknownEvent>(
            WebSocketNotificationDecoder.decode("""{"type":"future_event","data":null}"""),
        )
        assertIs<WebSocketNotificationDecodeOutcome.IgnoredUnknownEvent>(
            WebSocketNotificationDecoder.decode("""{"type":"future_event"}"""),
        )
        assertIs<WebSocketNotificationDecodeOutcome.ContractFailure>(
            WebSocketNotificationDecoder.decode(
                """{"type":"companion_snapshot_revision","data":{}}""",
            ),
        )
        assertIs<WebSocketNotificationDecodeOutcome.ContractFailure>(
            WebSocketNotificationDecoder.decode(
                """{"type":"companion_snapshot_revision","data":{"revision":"a","revision":"b"}}""",
            ),
        )
    }

    @Test
    fun `targeted failures accept each closed source tuple`() {
        listOf(
            ExpectedSourceError(
                SourceErrorCode.SourceUnreachable,
                retryable = true,
                action = ProtocolErrorAction.Retry,
            ),
            ExpectedSourceError(
                SourceErrorCode.SourceRateLimited,
                retryable = true,
                action = ProtocolErrorAction.Retry,
            ),
            ExpectedSourceError(
                SourceErrorCode.SourceAuthenticationFailed,
                retryable = false,
                action = ProtocolErrorAction.UseFullClient,
            ),
            ExpectedSourceError(
                SourceErrorCode.SourceConfigurationChanged,
                retryable = false,
                action = ProtocolErrorAction.UseFullClient,
            ),
            ExpectedSourceError(
                SourceErrorCode.SourceUnexpectedError,
                retryable = false,
                action = ProtocolErrorAction.None,
            ),
        ).forEach { expected ->
            val outcome =
                WebSocketNotificationDecoder.decode(
                    failedRefresh(
                        target = SOURCE_TARGET,
                        code = expected.code.wireValue,
                        retryable = expected.retryable,
                        action = expected.action.wireValue,
                    ),
                )
            val operation = outcome.refreshOperation()
            val error = assertIs<RefreshOperationError.Source>(operation.error)
            assertEquals(expected.code, error.code)
            assertEquals(expected.retryable, error.retryable)
            assertEquals(expected.action, error.action)
        }
    }

    @Test
    fun `refresh errors reject wrong target code family or tuple`() {
        listOf(
            failedRefresh(GLOBAL_TARGET, "source_authentication_failed", false, "use_full_client")
                .replace("\"progress\":null", "\"progress\":{\"completed\":1,\"total\":1}"),
            failedRefresh(SOURCE_TARGET, "source_refresh_failed", true, "retry"),
            failedRefresh(SOURCE_TARGET, "source_authentication_failed", true, "use_full_client"),
            failedRefresh(GLOBAL_TARGET, "operation_interrupted", false, "retry"),
        ).forEach { payload ->
            assertIs<WebSocketNotificationDecodeOutcome.ContractFailure>(
                WebSocketNotificationDecoder.decode(payload),
            )
        }
    }

    @Test
    fun `operation interrupted is valid for both refresh target kinds`() {
        listOf(GLOBAL_TARGET, SOURCE_TARGET).forEach { target ->
            val payload =
                failedRefresh(target, "operation_interrupted", true, "retry").let {
                    if (target == GLOBAL_TARGET) {
                        it.replace("\"progress\":null", "\"progress\":{\"completed\":1,\"total\":2}")
                    } else {
                        it
                    }
                }
            val error =
                assertIs<RefreshOperationError.Operation>(
                    WebSocketNotificationDecoder.decode(payload).refreshOperation().error,
                )
            assertEquals(true, error.retryable)
            assertEquals(ProtocolErrorAction.Retry, error.action)
        }
    }

    @Test
    fun `unknown future source error degrades to safe unexpected source failure`() {
        val operation =
            WebSocketNotificationDecoder
                .decode(
                    failedRefresh(SOURCE_TARGET, "future_source_error", true, "future_action"),
                ).refreshOperation()
        val error = assertIs<RefreshOperationError.Source>(operation.error)

        assertEquals(SourceErrorCode.SourceUnexpectedError, error.code)
        assertEquals(false, error.retryable)
        assertEquals(ProtocolErrorAction.None, error.action)
    }

    @Test
    fun `unknown future global error degrades to safe unexpected operation failure`() {
        val payload =
            failedRefresh(GLOBAL_TARGET, "future_operation_error", true, "future_action")
                .replace("\"progress\":null", "\"progress\":{\"completed\":1,\"total\":2}")
        val error =
            assertIs<RefreshOperationError.UnexpectedOperation>(
                WebSocketNotificationDecoder.decode(payload).refreshOperation().error,
            )

        assertEquals(false, error.retryable)
        assertEquals(ProtocolErrorAction.None, error.action)
    }

    @Test
    fun `known events reject quoted numeric and boolean scalars`() {
        val canonical =
            failedRefresh(
                GLOBAL_TARGET,
                "source_refresh_failed",
                true,
                "retry",
            )
        listOf(
            canonical.replace("\"version\":2", "\"version\":\"2\""),
            canonical.replace("\"created_at\":1786550400", "\"created_at\":\"1786550400\""),
            canonical.replace("\"retryable\":true", "\"retryable\":\"true\""),
        ).forEach { payload ->
            assertIs<WebSocketNotificationDecodeOutcome.ContractFailure>(
                WebSocketNotificationDecoder.decode(payload),
            )
        }
    }

    @Test
    fun `known refresh events enforce lifecycle and progress invariants`() {
        val running = runningRefresh(GLOBAL_TARGET)
        listOf(
            running.replace("\"result_snapshot_revision\":null", REVISION_FIELD),
            running.replace("\"progress\":{\"completed\":1,\"total\":2}", "\"progress\":null"),
            failedRefresh(GLOBAL_TARGET, "source_refresh_failed", true, "retry")
                .replace("\"progress\":null", "\"progress\":{\"completed\":1,\"total\":2}"),
            failedRefresh(GLOBAL_TARGET, "source_refresh_failed", true, "retry")
                .replace("\"progress\":null", "\"progress\":{\"completed\":2,\"total\":2}"),
            succeededRefresh(GLOBAL_TARGET, startedAt = "null", progress = "{\"completed\":2,\"total\":2}"),
            succeededRefresh(
                GLOBAL_TARGET,
                startedAt = "1786550401",
                progress = "{\"completed\":1,\"total\":2}",
            ),
            succeededRefresh(
                SOURCE_TARGET,
                startedAt = "1786550401",
                progress = "{\"completed\":1,\"total\":1}",
            ),
            running.replace(GLOBAL_TARGET, "{\"kind\":\"global\",\"source_id\":null}"),
        ).forEach { payload ->
            assertIs<WebSocketNotificationDecodeOutcome.ContractFailure>(
                WebSocketNotificationDecoder.decode(payload),
            )
        }

        val validPartialFailure =
            failedRefresh(
                GLOBAL_TARGET,
                "source_refresh_failed",
                true,
                "retry",
            ).replace(
                "\"progress\":null",
                "\"progress\":{\"completed\":2,\"total\":2}",
            ).replace("\"result_snapshot_revision\":null", REVISION_FIELD)
        assertIs<WebSocketNotificationDecodeOutcome.Decoded>(
            WebSocketNotificationDecoder.decode(validPartialFailure),
        )
    }

    @Test
    fun `queued source operation accepts null progress`() {
        val operation = WebSocketNotificationDecoder.decode(queuedSourceRefresh()).refreshOperation()

        assertEquals(RefreshOperationState.QUEUED, operation.state)
        assertIs<RefreshTarget.Source>(operation.target)
        assertNull(operation.progress)
        assertNull(operation.startedAtEpochSeconds)
        assertNull(operation.finishedAtEpochSeconds)
    }

    @Test
    fun `notification string representations redact opaque identifiers`() {
        val snapshot = CompanionNotification.SnapshotRevisionAvailable("seeded_revision")
        val source = RefreshTarget.Source("seeded_source")
        val operation =
            RefreshOperationNotification(
                operationId = "seeded_operation",
                version = 1,
                createdAtEpochSeconds = 0,
                startedAtEpochSeconds = null,
                finishedAtEpochSeconds = null,
                target = source,
                state = RefreshOperationState.QUEUED,
                progress = null,
                resultSnapshotRevision = "seeded_result_revision",
                error = null,
            )
        val changed = CompanionNotification.RefreshOperationChanged(operation)
        val decoded = WebSocketNotificationDecodeOutcome.Decoded(snapshot)

        assertEquals("SnapshotRevisionAvailable(redacted)", snapshot.toString())
        assertEquals("Source(redacted)", source.toString())
        assertEquals("RefreshOperationNotification(redacted)", operation.toString())
        assertEquals("RefreshOperationChanged(redacted)", changed.toString())
        assertEquals("Decoded(redacted)", decoded.toString())
        listOf(
            snapshot.toString(),
            source.toString(),
            operation.toString(),
            changed.toString(),
            decoded.toString(),
        ).forEach { diagnostic ->
            assertFalse(diagnostic.contains("seeded_"))
        }
    }

    @Test
    fun `oversized or excessively nested events fail closed`() {
        val oversized =
            "{\"type\":\"future_event\",\"data\":\"" +
                "x".repeat(65_536) + "\"}"
        val nested =
            "{\"type\":\"future_event\",\"data\":" +
                "[".repeat(65) + "0" + "]".repeat(65) + "}"
        assertIs<WebSocketNotificationDecodeOutcome.ContractFailure>(
            WebSocketNotificationDecoder.decode(oversized),
        )
        assertIs<WebSocketNotificationDecodeOutcome.ContractFailure>(
            WebSocketNotificationDecoder.decode(nested),
        )
    }
}

private fun WebSocketNotificationDecodeOutcome.refreshOperation(): RefreshOperationNotification =
    assertIs<CompanionNotification.RefreshOperationChanged>(
        assertIs<WebSocketNotificationDecodeOutcome.Decoded>(this).notification,
    ).operation

private data class ExpectedSourceError(
    val code: SourceErrorCode,
    val retryable: Boolean,
    val action: ProtocolErrorAction,
)

private fun failedRefresh(
    target: String,
    code: String,
    retryable: Boolean,
    action: String,
): String =
    """{
  "type":"companion_refresh_operation",
  "data":{
    "operation_id":"oKGio6SlpqeoqaqrrK2urw",
    "version":2,
    "created_at":1786550400,
    "started_at":1786550401,
    "finished_at":1786550402,
    "target":$target,
    "state":"failed",
    "progress":null,
    "result_snapshot_revision":null,
    "error":{"code":"$code","retryable":$retryable,"action":"$action"}
  }
}"""

private fun runningRefresh(target: String): String =
    """{
  "type":"companion_refresh_operation",
  "data":{
    "operation_id":"oKGio6SlpqeoqaqrrK2urw",
    "version":3,
    "created_at":1786550400,
    "started_at":1786550401,
    "finished_at":null,
    "target":$target,
    "state":"running",
    "progress":{"completed":1,"total":2},
    "result_snapshot_revision":null,
    "error":null
  }
}"""

private fun queuedSourceRefresh(): String =
    """{
  "type":"companion_refresh_operation",
  "data":{
    "operation_id":"oKGio6SlpqeoqaqrrK2urw",
    "version":1,
    "created_at":1786550400,
    "started_at":null,
    "finished_at":null,
    "target":$SOURCE_TARGET,
    "state":"queued",
    "progress":null,
    "result_snapshot_revision":null,
    "error":null
  }
}"""

private fun succeededRefresh(
    target: String,
    startedAt: String,
    progress: String,
): String =
    """{
  "type":"companion_refresh_operation",
  "data":{
    "operation_id":"oKGio6SlpqeoqaqrrK2urw",
    "version":3,
    "created_at":1786550400,
    "started_at":$startedAt,
    "finished_at":1786550402,
    "target":$target,
    "state":"succeeded",
    "progress":$progress,
    "result_snapshot_revision":null,
    "error":null
  }
}"""

private const val GLOBAL_TARGET: String = """{"kind":"global"}"""
private const val SOURCE_TARGET: String =
    """{"kind":"source","source_id":"oKGio6SlpqeoqaqrrK2urw"}"""
private const val REVISION_FIELD: String =
    """"result_snapshot_revision":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8""""
