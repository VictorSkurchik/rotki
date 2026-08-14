package org.rotki.mobile.core.protocol.dto

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.rotki.mobile.core.protocol.testing.ProtocolFixtureData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WebSocketNotificationFixtureTest {
    @Test
    fun `both canonical WebSocket fixtures decode to typed notifications`() {
        val examples =
            ProtocolFixtureData.golden
                .getValue("websocket_examples")
                .jsonArray
                .map { element -> element.jsonObject }
                .associateBy { example -> example.getValue("id").jsonPrimitive.content }
        assertEquals(
            setOf("companion_snapshot_revision", "companion_refresh_operation"),
            examples.keys,
        )

        val snapshot =
            WebSocketNotificationDecoder.decode(
                examples.getValue("companion_snapshot_revision").getValue("payload").toString(),
            )
        val refresh =
            WebSocketNotificationDecoder.decode(
                examples.getValue("companion_refresh_operation").getValue("payload").toString(),
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
        val progress = assertIs<RefreshProgress>(operation.progress)
        assertEquals(1, progress.completed)
        assertEquals(2, progress.total)
    }
}
