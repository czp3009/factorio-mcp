package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*

internal fun uiSelector(text: String? = null, type: String? = null) = buildJsonObject {
    putJsonArray("path") {
        addJsonObject {
            put("axis", "descendant")
            putJsonObject("match") {
                text?.let { put("text", it) }
                type?.let { put("native_type", it) }
            }
        }
    }
}

/** Rebuild a live child/type/position selector from complete native relationships, without retaining IDs. */
internal fun uiSelectorForNode(snapshot: JsonObject, id: Int): JsonObject {
    require(!snapshot.getValue("truncated").jsonPrimitive.boolean) { "Selector reconstruction requires a complete tree" }
    val nodes = snapshot.getValue("nodes").jsonArray.map { it.jsonObject }
    val byId = nodes.associateBy { it.getValue("id").jsonPrimitive.int }
    val path = mutableListOf<JsonObject>()
    var current: JsonObject? = byId[id] ?: error("Missing selector target")
    while (current != null) {
        path += current
        current = current["parent"]?.jsonPrimitive?.intOrNull?.let { byId[it] ?: error("Incomplete selector ancestry") }
        require(path.size <= nodes.size) { "Cyclic selector ancestry" }
    }
    return buildJsonObject {
        putJsonArray("path") {
            for (node in path.asReversed()) addJsonObject {
                val type = node.getValue("type")
                val siblings = nodes.filter { it["parent"] == node["parent"] && it.getValue("type") == type }
                put("axis", "child")
                putJsonObject("match") { put("native_type", type) }
                put("position", siblings.indexOf(node) + 1)
            }
        }
    }
}
