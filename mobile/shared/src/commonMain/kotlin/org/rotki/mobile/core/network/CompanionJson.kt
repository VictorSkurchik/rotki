package org.rotki.mobile.core.network

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

@OptIn(ExperimentalSerializationApi::class)
internal val CompanionJson: Json =
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
