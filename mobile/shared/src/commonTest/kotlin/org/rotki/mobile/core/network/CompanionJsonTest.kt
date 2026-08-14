package org.rotki.mobile.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class CompanionJsonTest {
    @Test
    fun ignoresAdditiveMembersButRequiresEveryDeclaredMember() {
        val decoded =
            CompanionJson.decodeFromString<StrictFixture>(
                """{"required":"present","required_nullable":null,"future":true}""",
            )
        assertEquals("present", decoded.required)
        assertNull(decoded.requiredNullable)

        assertFailsWith<SerializationException> {
            CompanionJson.decodeFromString<StrictFixture>("""{"required":"present"}""")
        }
    }

    @Test
    fun rejectsLenientJsonAndDoesNotEmbedSourceInDiagnostics() {
        val secretMarker = "must-not-enter-diagnostics"
        val failure =
            assertFailsWith<SerializationException> {
                CompanionJson.decodeFromString<StrictFixture>(
                    """{"required":"$secretMarker","required_nullable":null,}""",
                )
            }
        assertFalse(failure.message.orEmpty().contains(secretMarker))
    }

    @Serializable
    private data class StrictFixture(
        val required: String,
        @SerialName("required_nullable")
        val requiredNullable: String?,
    )
}
