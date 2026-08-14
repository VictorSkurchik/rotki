package org.rotki.mobile.core.protocol.dto

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.rotki.mobile.core.protocol.generated.SourceErrorCode
import org.rotki.mobile.core.protocol.testing.ProtocolFixtureData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WebSocketNotificationDtoTest {
    @Test
    fun `both canonical WebSocket fixtures decode to typed notifications`() {
        val examples = ProtocolFixtureData.golden.getValue("websocket_examples").jsonArray
        assertEquals(2, examples.size)

        val snapshot =
            WebSocketNotificationDecoder.decode(
                examples[0].jsonObject.getValue("payload").toString(),
            )
        val refresh =
            WebSocketNotificationDecoder.decode(
                examples[1].jsonObject.getValue("payload").toString(),
            )

        assertIs<CompanionNotification.SnapshotRevisionAvailable>(
            assertIs<WebSocketNotificationDecodeOutcome.Decoded>(snapshot).notification,
        )
        val operation =
            assertIs<CompanionNotification.RefreshOperationChanged>(
                assertIs<WebSocketNotificationDecodeOutcome.Decoded>(refresh).notification,
            ).operation
        assertEquals(RefreshOperationState.RUNNING, operation.state)
        assertEquals(3, operation.version)
        assertEquals(RefreshTarget.Global, operation.target)
        assertEquals(RefreshProgress(1, 2), operation.progress)
    }

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
            Triple("source_authentication_failed", false, "use_full_client"),
            Triple("source_configuration_changed", false, "use_full_client"),
        ).forEach { (code, retryable, action) ->
            val outcome =
                WebSocketNotificationDecoder.decode(
                    failedRefresh(
                        target = SOURCE_TARGET,
                        code = code,
                        retryable = retryable,
                        action = action,
                    ),
                )
            val operation =
                assertIs<CompanionNotification.RefreshOperationChanged>(
                    assertIs<WebSocketNotificationDecodeOutcome.Decoded>(outcome).notification,
                ).operation
            val error = assertIs<RefreshOperationError.Source>(operation.error)
            assertEquals(
                if (code == "source_authentication_failed") {
                    SourceErrorCode.SourceAuthenticationFailed
                } else {
                    SourceErrorCode.SourceConfigurationChanged
                },
                error.code,
            )
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
            val outcome =
                WebSocketNotificationDecoder.decode(
                    payload,
                )
            val operation =
                assertIs<CompanionNotification.RefreshOperationChanged>(
                    assertIs<WebSocketNotificationDecodeOutcome.Decoded>(outcome).notification,
                ).operation
            assertIs<RefreshOperationError.Operation>(operation.error)
        }
    }

    @Test
    fun `unknown future source error degrades to safe unexpected source failure`() {
        val outcome =
            WebSocketNotificationDecoder.decode(
                failedRefresh(SOURCE_TARGET, "future_source_error", true, "future_action"),
            )
        val operation =
            assertIs<CompanionNotification.RefreshOperationChanged>(
                assertIs<WebSocketNotificationDecodeOutcome.Decoded>(outcome).notification,
            ).operation
        val error = assertIs<RefreshOperationError.Source>(operation.error)
        assertEquals(SourceErrorCode.SourceUnexpectedError, error.code)
        assertEquals(false, error.retryable)
    }

    @Test
    fun `unknown future global error degrades to safe unexpected operation failure`() {
        val payload =
            failedRefresh(GLOBAL_TARGET, "future_operation_error", true, "future_action")
                .replace("\"progress\":null", "\"progress\":{\"completed\":1,\"total\":2}")
        val outcome = WebSocketNotificationDecoder.decode(payload)
        val operation =
            assertIs<CompanionNotification.RefreshOperationChanged>(
                assertIs<WebSocketNotificationDecodeOutcome.Decoded>(outcome).notification,
            ).operation

        val error = assertIs<RefreshOperationError.UnexpectedOperation>(operation.error)
        assertEquals(false, error.retryable)
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
        val running =
            ProtocolFixtureData.golden
                .getValue("websocket_examples")
                .jsonArray[1]
                .jsonObject
                .getValue("payload")
                .toString()
        listOf(
            running.replace("\"result_snapshot_revision\":null", REVISION_FIELD),
            running.replace("\"progress\":{\"completed\":1,\"total\":2}", "\"progress\":null"),
            failedRefresh(GLOBAL_TARGET, "source_refresh_failed", true, "retry")
                .replace("\"progress\":null", "\"progress\":{\"completed\":1,\"total\":2}"),
            failedRefresh(GLOBAL_TARGET, "source_refresh_failed", true, "retry")
                .replace("\"progress\":null", "\"progress\":{\"completed\":2,\"total\":2}"),
            succeededRefresh(GLOBAL_TARGET, startedAt = "null", progress = "{\"completed\":2,\"total\":2}"),
            succeededRefresh(GLOBAL_TARGET, startedAt = "1786550401", progress = "{\"completed\":1,\"total\":2}"),
            succeededRefresh(SOURCE_TARGET, startedAt = "1786550401", progress = "{\"completed\":1,\"total\":1}"),
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
    fun `notification string representations redact opaque identifiers`() {
        val notification = CompanionNotification.SnapshotRevisionAvailable("seeded_revision")
        kotlin.test.assertFalse(notification.toString().contains("seeded_revision"))
        kotlin.test.assertFalse(
            RefreshTarget.Source("seeded_source").toString().contains("seeded_source"),
        )
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
