package org.rotki.mobile.core.protocol.generated

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.rotki.mobile.core.protocol.testing.ProtocolFixtureData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeneratedProtocolVocabularyTest {
    @Test
    fun `generated declarations exactly match canonical vocabulary and language names`() {
        val vocabulary = ProtocolFixtureData.vocabulary
        val names = ProtocolFixtureData.generatedNames.getValue("domains").jsonObject

        assertEquals(1, PROTOCOL_VOCABULARY_SCHEMA_VERSION)
        assertEquals(setOf(1), SUPPORTED_PROTOCOL_VERSIONS)
        assertEquals(setOf(1000, 1006, 1008, 1012), WEBSOCKET_CLOSE_CODES)
        assertEquals("ecdsa-p256-sha256-p1363", DEVICE_PROOF_ALGORITHM)
        assertEquals(
            vocabulary.stringArray("root_states"),
            ProtocolRootState.entries.mapTo(mutableSetOf(), ProtocolRootState::wireValue),
        )
        assertEquals(
            vocabulary.stringArray("http_error_codes"),
            HttpErrorCode.entries.mapTo(mutableSetOf(), HttpErrorCode::wireValue),
        )
        assertEquals(
            vocabulary.stringArray("error_actions"),
            ProtocolErrorAction.entries.mapTo(mutableSetOf(), ProtocolErrorAction::wireValue),
        )
        assertEquals(
            vocabulary.stringArray("websocket_event_types"),
            WebSocketEventType.entries.mapTo(mutableSetOf(), WebSocketEventType::wireValue),
        )

        val enumDomains =
            mapOf(
                "auth_realms" to ProtocolAuthRealm.entries.associate { it.wireValue to it.name },
                "authorities" to ProtocolAuthority.entries.associate { it.wireValue to it.name },
                "capabilities" to ProtocolCapability.entries.associate { it.wireValue to it.name },
                "device_session_states" to DeviceSessionState.entries.associate { it.wireValue to it.name },
                "error_actions" to ProtocolErrorAction.entries.associate { it.wireValue to it.name },
                "http_error_codes" to HttpErrorCode.entries.associate { it.wireValue to it.name },
                "operation_error_codes" to OperationErrorCode.entries.associate { it.wireValue to it.name },
                "platforms" to CompanionPlatform.entries.associate { it.wireValue to it.name },
                "root_states" to ProtocolRootState.entries.associate { it.wireValue to it.name },
                "source_error_codes" to SourceErrorCode.entries.associate { it.wireValue to it.name },
                "websocket_event_types" to WebSocketEventType.entries.associate { it.wireValue to it.name },
            )
        enumDomains.forEach { (domain, actual) ->
            val expected =
                names.getValue(domain).jsonObject.mapValues { (_, value) ->
                    value.jsonObject
                        .getValue("kotlin")
                        .jsonPrimitive.content
                }
            assertEquals(expected, actual, domain)
        }
        assertTrue(ProtocolCapability.entries.all { capability -> capability.minimumVersion == 1 })
    }

    @Test
    fun `all generated fixture documents retain schema version one`() {
        listOf(
            ProtocolFixtureData.vocabulary,
            ProtocolFixtureData.generatedNames,
            ProtocolFixtureData.cases,
            ProtocolFixtureData.golden,
            ProtocolFixtureData.clientPolicy,
        ).forEach { document ->
            assertEquals(
                1,
                document
                    .getValue("schema_version")
                    .jsonPrimitive.content
                    .toInt(),
            )
        }
    }
}

private fun kotlinx.serialization.json.JsonObject.stringArray(name: String): Set<String> =
    getValue(name).jsonArray.mapTo(mutableSetOf()) { element -> element.jsonPrimitive.content }
