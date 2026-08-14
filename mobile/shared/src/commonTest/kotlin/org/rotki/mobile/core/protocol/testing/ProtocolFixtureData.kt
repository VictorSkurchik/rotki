package org.rotki.mobile.core.protocol.testing

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.rotki.mobile.core.protocol.CompanionJson
import org.rotki.mobile.core.protocol.generated.PROTOCOL_CLIENT_POLICY_CASES_JSON
import org.rotki.mobile.core.protocol.generated.PROTOCOL_GENERATED_NAMES_JSON
import org.rotki.mobile.core.protocol.generated.PROTOCOL_GOLDEN_VECTORS_JSON
import org.rotki.mobile.core.protocol.generated.PROTOCOL_P0_1_CASES_JSON
import org.rotki.mobile.core.protocol.generated.PROTOCOL_VOCABULARY_JSON

internal object ProtocolFixtureData {
    internal val vocabulary: JsonObject = parse(PROTOCOL_VOCABULARY_JSON)
    internal val generatedNames: JsonObject = parse(PROTOCOL_GENERATED_NAMES_JSON)
    internal val cases: JsonObject = parse(PROTOCOL_P0_1_CASES_JSON)
    internal val golden: JsonObject = parse(PROTOCOL_GOLDEN_VECTORS_JSON)
    internal val clientPolicy: JsonObject = parse(PROTOCOL_CLIENT_POLICY_CASES_JSON)

    internal fun successExample(id: String): JsonObject =
        golden
            .getValue("success_examples")
            .jsonArray
            .map { element -> element.jsonObject }
            .single { example -> example.getValue("id").jsonPrimitive.content == id }

    private fun parse(raw: String): JsonObject = CompanionJson.parseToJsonElement(raw).jsonObject
}
