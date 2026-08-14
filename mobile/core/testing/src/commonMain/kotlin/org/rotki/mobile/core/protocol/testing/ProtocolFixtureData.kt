package org.rotki.mobile.core.protocol.testing

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.rotki.mobile.core.protocol.CompanionJsonCodec
import org.rotki.mobile.core.protocol.generated.PROTOCOL_CLIENT_POLICY_CASES_JSON
import org.rotki.mobile.core.protocol.generated.PROTOCOL_GENERATED_NAMES_JSON
import org.rotki.mobile.core.protocol.generated.PROTOCOL_GOLDEN_VECTORS_JSON
import org.rotki.mobile.core.protocol.generated.PROTOCOL_P0_1_CASES_JSON
import org.rotki.mobile.core.protocol.generated.PROTOCOL_VOCABULARY_JSON

public object ProtocolFixtureData {
    public val vocabulary: JsonObject = parse(PROTOCOL_VOCABULARY_JSON)
    public val generatedNames: JsonObject = parse(PROTOCOL_GENERATED_NAMES_JSON)
    public val cases: JsonObject = parse(PROTOCOL_P0_1_CASES_JSON)
    public val golden: JsonObject = parse(PROTOCOL_GOLDEN_VECTORS_JSON)
    public val clientPolicy: JsonObject = parse(PROTOCOL_CLIENT_POLICY_CASES_JSON)

    public fun successExample(id: String): JsonObject =
        golden
            .getValue("success_examples")
            .jsonArray
            .map { element -> element.jsonObject }
            .single { example -> example.getValue("id").jsonPrimitive.content == id }

    public fun <T> decodeCompanionJson(
        text: String,
        deserializer: DeserializationStrategy<T>,
    ): T =
        CompanionJsonCodec.decodeFromJsonElement(
            deserializer,
            CompanionJsonCodec.parseToJsonElement(text),
        )

    private fun parse(raw: String): JsonObject = CompanionJsonCodec.parseToJsonElement(raw).jsonObject
}
