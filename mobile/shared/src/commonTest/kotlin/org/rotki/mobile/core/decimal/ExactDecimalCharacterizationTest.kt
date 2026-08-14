package org.rotki.mobile.core.decimal

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.rotki.mobile.core.decimal.internal.ExactDecimalException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExactDecimalCharacterizationTest {
    private val json = Json

    @Test
    fun matchesEveryPythonFvalVector() {
        val document = json.parseToJsonElement(EXACT_DECIMAL_VECTORS_JSON).jsonObject
        val contract = document.requiredObject("contract")
        assertEquals(78, contract.requiredInt("max_significant_digits"))
        assertEquals(2048, contract.requiredInt("max_input_length"))
        assertEquals("half_even", contract.requiredObject("division").requiredString("rounding"))

        document.requiredArray("cases").forEach { element ->
            val case = element.jsonObject
            val id = case.requiredString("id")
            assertEquals(case.requiredObject("expect"), execute(case), id)
        }
    }

    @Test
    fun serializesOnlyAsCanonicalJsonString() {
        val value = ExactDecimal.parse("123.4500")
        assertEquals("\"123.45\"", json.encodeToString(value))
        assertEquals(value, json.decodeFromString<ExactDecimal>("\"123.4500\""))
        assertFailsWith<SerializationException> {
            json.decodeFromString<ExactDecimal>("123.45")
        }
    }

    @Test
    fun exposesProjectOwnedOperations() {
        assertTrue(ExactDecimal.parse("1") < ExactDecimal.parse("2"))
        assertEquals("3", (ExactDecimal.parse("1") + ExactDecimal.parse("2")).toString())
        assertEquals(ExactDecimal.parse("1"), ExactDecimal.parse("1.00"))
        assertEquals(ExactDecimal.parse("1").hashCode(), ExactDecimal.parse("1.00").hashCode())
    }

    private fun execute(case: JsonObject): JsonObject =
        try {
            val args = case.requiredObject("args")
            when (case.requiredString("op")) {
                "parse" -> {
                    success("canonical", parse(args.required("value")).toString())
                }

                "inspect" -> {
                    inspect(parse(args.required("value")))
                }

                "compare" -> {
                    compare(args)
                }

                "add" -> {
                    binary(args) { left, right -> left + right }
                }

                "subtract" -> {
                    binary(args) { left, right -> left - right }
                }

                "multiply" -> {
                    binary(args) { left, right -> left * right }
                }

                "divide" -> {
                    binary(args) { left, right -> left / right }
                }

                "round" -> {
                    success(
                        "text",
                        parse(args.required("value")).toDisplayString(args.requiredInt("fraction_digits")),
                    )
                }

                "sort" -> {
                    sort(args)
                }

                "sum" -> {
                    sum(args)
                }

                "serialize" -> {
                    success(
                        "json",
                        json.encodeToString(parse(args.required("value"))),
                    )
                }

                else -> {
                    error("Unknown vector operation: ${case.requiredString("op")}")
                }
            }
        } catch (error: ExactDecimalException) {
            failure(error.code.wireValue)
        } catch (error: VectorInputException) {
            failure(error.code)
        }

    private fun parse(element: JsonElement): ExactDecimal {
        if (element !is JsonPrimitive || !element.isString) {
            throw VectorInputException("invalid_type")
        }
        return ExactDecimal.parse(element.content)
    }

    private fun inspect(value: ExactDecimal): JsonObject =
        buildJsonObject {
            put("status", "ok")
            put("canonical", value.toString())
            put("sign", value.sign.name.lowercase())
            put("is_zero", value.isZero)
            put("magnitude", value.magnitude.toString())
            put("canonical_scale", value.canonicalScale)
        }

    private fun compare(args: JsonObject): JsonObject {
        val result = parse(args.required("lhs")).compareTo(parse(args.required("rhs")))
        val ordering =
            when {
                result < 0 -> "less"
                result > 0 -> "greater"
                else -> "equal"
            }
        return success("ordering", ordering)
    }

    private fun binary(
        args: JsonObject,
        operation: (ExactDecimal, ExactDecimal) -> ExactDecimal,
    ): JsonObject =
        success(
            "canonical",
            operation(parse(args.required("lhs")), parse(args.required("rhs"))).toString(),
        )

    private fun sort(args: JsonObject): JsonObject {
        val values =
            args.requiredArray("values").map { element ->
                val item = element.jsonObject
                item.requiredString("id") to parse(item.required("value"))
            }
        val ordered = values.sortedWith(compareBy { it.second }).map { it.first }
        return buildJsonObject {
            put("status", "ok")
            put("ordered_ids", buildJsonArray { ordered.forEach { add(JsonPrimitive(it)) } })
        }
    }

    private fun sum(args: JsonObject): JsonObject {
        val total =
            args.requiredArray("values").fold(ExactDecimal.ZERO) { accumulator, element ->
                accumulator + parse(element)
            }
        return success("canonical", total.toString())
    }

    private fun success(
        name: String,
        value: String,
    ): JsonObject =
        buildJsonObject {
            put("status", "ok")
            put(name, value)
        }

    private fun failure(code: String): JsonObject =
        buildJsonObject {
            put("status", "error")
            put("code", code)
        }
}

private class VectorInputException(
    val code: String,
) : IllegalArgumentException(code)

private fun JsonObject.required(name: String): JsonElement =
    requireNotNull(this[name]) { "Missing vector field: $name" }

private fun JsonObject.requiredObject(name: String): JsonObject = required(name).jsonObject

private fun JsonObject.requiredArray(name: String): JsonArray = required(name).jsonArray

private fun JsonObject.requiredString(name: String): String = required(name).jsonPrimitive.content

private fun JsonObject.requiredInt(name: String): Int = required(name).jsonPrimitive.int
