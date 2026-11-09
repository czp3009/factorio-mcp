package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*

/** Normalize only MCP-owned array envelopes; attribute values retain the game's JSON encoding. */
internal class GuiSnapshot(value: String) {
    private val root = Json.parseToJsonElement(value).jsonObject
    val fields = array(root.getValue("fields"))
    val depth = root.getValue("depth").intArgument().also { require(it >= 0) }
    private val elements = root.getValue("elements").jsonObject

    fun element(index: Long): JsonObject? = elements[index.toString()]?.jsonObject?.let { value ->
        require(value.getValue("index").jsonPrimitive.long == index) { "Script GUI index differs from its widget association" }
        if ("children" in value) JsonObject(value + ("children" to array(value.getValue("children")))) else value
    }

    private fun array(value: JsonElement): JsonArray = when (value) {
        is JsonArray -> value
        is JsonObject -> {
            require(value.isEmpty()) { "Invalid GUI relationship/field array" }
            JsonArray(emptyList())
        }
        else -> error("Invalid GUI relationship/field array")
    }
}
