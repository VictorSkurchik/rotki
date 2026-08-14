@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package org.rotki.mobile.core.protocol

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.native.HiddenFromObjC

@OptIn(ExperimentalSerializationApi::class)
private val companionJson: Json =
    Json {
        allowComments = false
        allowSpecialFloatingPointValues = false
        allowStructuredMapKeys = false
        allowTrailingComma = false
        coerceInputValues = false
        decodeEnumsCaseInsensitive = false
        encodeDefaults = true
        exceptionsWithDebugInfo = false
        explicitNulls = true
        ignoreUnknownKeys = true
        isLenient = false
        prettyPrint = false
    }

/**
 * Kotlin-only access to the strict Companion wire codec without exposing its format instance.
 * Input text, parsed elements, and encoded bytes may contain sensitive protocol material and must
 * never be logged or persisted.
 */
@HiddenFromObjC
public object CompanionJsonCodec {
    public fun <T> encodeToByteArray(
        serializer: SerializationStrategy<T>,
        value: T,
    ): ByteArray = companionJson.encodeToString(serializer, value).encodeToByteArray()

    public fun parseToJsonElement(text: String): JsonElement = companionJson.parseToJsonElement(text)

    public fun <T> decodeFromJsonElement(
        deserializer: DeserializationStrategy<T>,
        element: JsonElement,
    ): T = companionJson.decodeFromJsonElement(deserializer, element)
}
