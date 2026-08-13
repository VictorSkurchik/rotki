package org.rotki.mobile.core.protocol

import org.rotki.mobile.core.protocol.generated.ProtocolClientInputLimits

internal fun hasDuplicateJsonMember(text: String): Boolean = try {
    DuplicateJsonMemberScanner(text).scan()
    false
} catch (_: DuplicateJsonMember) {
    true
} catch (_: IllegalArgumentException) {
    false
}

internal fun hasValidJsonSyntax(text: String): Boolean = try {
    DuplicateJsonMemberScanner(text).scan()
    true
} catch (_: DuplicateJsonMember) {
    true
} catch (_: IllegalArgumentException) {
    false
}

private class DuplicateJsonMemberScanner(private val text: String) {
    private var index: Int = 0

    fun scan(): Unit {
        skipWhitespace()
        scanValue(depth = 0)
        skipWhitespace()
        require(index == text.length)
    }

    private fun scanValue(depth: Int): Unit {
        require(index < text.length)
        when (text[index]) {
            '{' -> scanObject(depth + 1)
            '[' -> scanArray(depth + 1)
            '"' -> scanString()
            't' -> scanLiteral("true")
            'f' -> scanLiteral("false")
            'n' -> scanLiteral("null")
            else -> scanNumber()
        }
    }

    private fun scanObject(depth: Int): Unit {
        require(depth <= ProtocolClientInputLimits.MaximumJsonNestingDepth)
        index += 1
        skipWhitespace()
        if (consume('}')) return
        val keys: MutableSet<String> = mutableSetOf()
        while (true) {
            require(index < text.length && text[index] == '"')
            val key = scanString()
            if (!keys.add(key)) throw DuplicateJsonMember
            skipWhitespace()
            require(consume(':'))
            skipWhitespace()
            scanValue(depth)
            skipWhitespace()
            if (consume('}')) return
            require(consume(','))
            skipWhitespace()
        }
    }

    private fun scanArray(depth: Int): Unit {
        require(depth <= ProtocolClientInputLimits.MaximumJsonNestingDepth)
        index += 1
        skipWhitespace()
        if (consume(']')) return
        while (true) {
            scanValue(depth)
            skipWhitespace()
            if (consume(']')) return
            require(consume(','))
            skipWhitespace()
        }
    }

    private fun scanString(): String {
        require(consume('"'))
        val result = StringBuilder()
        while (index < text.length) {
            val character = text[index++]
            when {
                character == '"' -> return result.toString()
                character == '\\' -> result.append(scanEscape())
                character.code < 0x20 -> throw IllegalArgumentException()
                else -> result.append(character)
            }
        }
        throw IllegalArgumentException()
    }

    private fun scanEscape(): Char {
        require(index < text.length)
        return when (val escaped = text[index++]) {
            '"', '\\', '/' -> escaped
            'b' -> '\b'
            'f' -> '\u000c'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> scanUnicodeEscape()
            else -> throw IllegalArgumentException()
        }
    }

    private fun scanUnicodeEscape(): Char {
        require(index + 4 <= text.length)
        var value = 0
        repeat(4) {
            value = value * 16 + text[index++].digitToIntOrNull(16).orInvalidJson()
        }
        return value.toChar()
    }

    private fun scanLiteral(literal: String): Unit {
        require(text.regionMatches(index, literal, 0, literal.length))
        index += literal.length
    }

    private fun scanNumber(): Unit {
        val start = index
        consume('-')
        if (consume('0')) {
            require(index >= text.length || !text[index].isDigit())
        } else {
            require(index < text.length && text[index] in '1'..'9')
            while (index < text.length && text[index].isDigit()) index += 1
        }
        if (consume('.')) {
            require(index < text.length && text[index].isDigit())
            while (index < text.length && text[index].isDigit()) index += 1
        }
        if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
            index += 1
            if (index < text.length && (text[index] == '+' || text[index] == '-')) index += 1
            require(index < text.length && text[index].isDigit())
            while (index < text.length && text[index].isDigit()) index += 1
        }
        require(index > start)
    }

    private fun skipWhitespace(): Unit {
        while (index < text.length && text[index] in JSON_WHITESPACE) index += 1
    }

    private fun consume(expected: Char): Boolean {
        if (index >= text.length || text[index] != expected) return false
        index += 1
        return true
    }
}

private fun Int?.orInvalidJson(): Int = this ?: throw IllegalArgumentException()

private data object DuplicateJsonMember : Throwable()

private val JSON_WHITESPACE: Set<Char> = setOf(' ', '\t', '\r', '\n')
