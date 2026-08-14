package org.rotki.mobile.core.ports

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.rotki.mobile.core.protocol.testing.ProtocolFixtureData
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationVisibilityPolicyFixtureTest {
    @Test
    fun allAuthoredLifecycleCasesExecuteUnchanged() {
        val cases =
            ProtocolFixtureData.clientPolicy
                .getValue("lifecycle_policy")
                .jsonObject
                .getValue("cases")
                .jsonArray
        assertEquals(3, cases.size)

        cases.forEach { element ->
            val case = element.jsonObject
            val state =
                when (case.string("visibility")) {
                    "active_foreground" -> ApplicationVisibilityState.ACTIVE_FOREGROUND
                    "inactive" -> ApplicationVisibilityState.INACTIVE
                    "background_or_locked" -> ApplicationVisibilityState.BACKGROUND_OR_LOCKED
                    else -> error("Unknown lifecycle fixture")
                }
            assertEquals(
                ApplicationVisibilityDecision(
                    networkAllowed = case.boolean("network_allowed"),
                    destroyBearer = case.boolean("destroy_bearer"),
                    discardPlaintext = case.boolean("discard_plaintext"),
                    requiresDeviceAuthenticationOnReturn =
                        case.boolean("requires_device_authentication_on_return"),
                ),
                ApplicationVisibilityPolicy.decide(state),
                case.string("id"),
            )
        }
    }
}

private fun kotlinx.serialization.json.JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

private fun kotlinx.serialization.json.JsonObject.boolean(name: String): Boolean = string(name).toBooleanStrict()
