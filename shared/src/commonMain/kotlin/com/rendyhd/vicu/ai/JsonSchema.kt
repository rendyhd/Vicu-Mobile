package com.rendyhd.vicu.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Checks a model's JSON against a schema of the subset every strict mode accepts
 * (`docs/ai-assistant-v1.md`, section 2.2, `schemaSubset` in the fixture): `type`, `properties`,
 * `required`, `additionalProperties: false`, `items`, `enum`, `description` and `anyOf`.
 */
object JsonSchema {
    /** The first problem with [value], phrased for the repair message, or null when it matches. */
    fun firstProblem(value: JsonElement, schema: JsonObject, path: String = ""): String? {
        schema["anyOf"]?.let { options ->
            val problems = options.jsonArray.map { firstProblem(value, it.jsonObject, path) }
            if (problems.any { it == null }) return null
            return problems.filterNotNull().firstOrNull { !it.endsWith(TYPE_SUFFIX) }
                ?: "${where(path)} is not ${options.jsonArray.joinToString(" or ") { typeName(it.jsonObject) }}"
        }
        val type = (schema["type"] as? JsonPrimitive)?.content
        if (type != null && !hasType(value, type)) return "${where(path)} is not $type$TYPE_SUFFIX"
        schema["enum"]?.let { allowed ->
            if (allowed.jsonArray.none { it == value }) {
                return "${where(path)} is not one of ${allowed.jsonArray.joinToString(", ") { it.toString() }}"
            }
        }
        when (type) {
            "object" -> {
                val obj = value.jsonObject
                val properties = schema["properties"]?.jsonObject ?: JsonObject(emptyMap())
                for (name in schema["required"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }) {
                    if (name !in obj) return "${where(join(path, name))} is missing"
                }
                val closed = (schema["additionalProperties"] as? JsonPrimitive)?.booleanOrNull == false
                for ((name, element) in obj) {
                    val propertySchema = properties[name]?.jsonObject
                    if (propertySchema == null) {
                        if (closed) return "${where(join(path, name))} is not allowed"
                        continue
                    }
                    firstProblem(element, propertySchema, join(path, name))?.let { return it }
                }
            }
            "array" -> {
                val items = schema["items"]?.jsonObject ?: return null
                value.jsonArray.forEachIndexed { index, element ->
                    firstProblem(element, items, "$path[$index]")?.let { return it }
                }
            }
        }
        return null
    }

    /** Problems with [schema] itself against the strict subset; empty when it is inside it. */
    fun subsetProblems(schema: JsonObject, allowedKeywords: Set<String>, path: String = "$"): List<String> {
        val problems = mutableListOf<String>()
        for (key in schema.keys) if (key !in allowedKeywords) problems += "$path: keyword $key"
        val type = (schema["type"] as? JsonPrimitive)?.content
        if (type == "object") {
            if ((schema["additionalProperties"] as? JsonPrimitive)?.booleanOrNull != false) {
                problems += "$path: additionalProperties is not false"
            }
            val properties = schema["properties"]?.jsonObject ?: JsonObject(emptyMap())
            val required = schema["required"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }.toSet()
            for ((name, child) in properties) {
                if (name !in required) problems += "$path.$name: not required"
                problems += subsetProblems(child.jsonObject, allowedKeywords, "$path.$name")
            }
        }
        schema["items"]?.let { problems += subsetProblems(it.jsonObject, allowedKeywords, "$path[]") }
        schema["anyOf"]?.jsonArray?.forEachIndexed { i, option ->
            problems += subsetProblems(option.jsonObject, allowedKeywords, "$path|$i")
        }
        return problems
    }

    private const val TYPE_SUFFIX = " (wrong type)"

    private fun hasType(value: JsonElement, type: String): Boolean = when (type) {
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        "null" -> value is JsonNull
        "string" -> value is JsonPrimitive && value !is JsonNull && value.isString
        "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
        "integer" -> value is JsonPrimitive && !value.isString && value !is JsonNull &&
            (value.longOrNull != null || value.doubleOrNull?.let { it == kotlin.math.floor(it) && !it.isInfinite() } == true)
        "number" -> value is JsonPrimitive && !value.isString && value !is JsonNull && value.doubleOrNull != null
        else -> true
    }

    private fun typeName(schema: JsonObject): String = (schema["type"] as? JsonPrimitive)?.content ?: "the expected shape"

    private fun join(path: String, name: String) = if (path.isEmpty()) name else "$path.$name"

    private fun where(path: String) = if (path.isEmpty()) "the answer" else path

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
