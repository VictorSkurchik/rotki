package org.rotki.mobile.core.protocol

import org.rotki.mobile.core.protocol.generated.ProtocolClientInputLimits
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DuplicateJsonMemberScannerTest {
    @Test
    fun acceptsStrictJsonWithoutDuplicateMembers() {
        listOf(
            "null",
            "true",
            "-12.5e+2",
            """{"text":"escaped\nvalue","nested":[{"value":1},false]}""",
        ).forEach { text ->
            assertTrue(hasValidJsonSyntax(text), text)
            assertFalse(hasDuplicateJsonMember(text), text)
        }
    }

    @Test
    fun detectsRecursiveAndEscapedEquivalentDuplicates() {
        listOf(
            """{"value":1,"value":2}""",
            """{"nested":{"value":1,"\u0076alue":2}}""",
            """[{"value":1,"value":2}]""",
        ).forEach { text ->
            assertTrue(hasValidJsonSyntax(text), text)
            assertTrue(hasDuplicateJsonMember(text), text)
        }
    }

    @Test
    fun rejectsMalformedJsonWithoutReportingDuplicates() {
        listOf(
            "",
            "01",
            """{"value":1,}""",
            """{"value":"\u00xz"}""",
            """[true false]""",
        ).forEach { text ->
            assertFalse(hasValidJsonSyntax(text), text)
            assertFalse(hasDuplicateJsonMember(text), text)
        }
    }

    @Test
    fun enforcesMaximumNestingDepth() {
        val maximumDepth = ProtocolClientInputLimits.MaximumJsonNestingDepth
        val atLimit = "[".repeat(maximumDepth) + "0" + "]".repeat(maximumDepth)
        val overLimit = "[$atLimit]"

        assertTrue(hasValidJsonSyntax(atLimit))
        assertFalse(hasValidJsonSyntax(overLimit))
    }
}
