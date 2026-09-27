package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*

internal fun inspectionPathSchema() = buildJsonObject {
    put("type", "array")
    put("maxItems", 12)
    put(
        "description",
        "inspect only. Ordered traversal reacquired from the root in this observation. A step selects a readable property, a collection index/key, or an admitted query method. Use mode:members to discover available names and signatures. Method arguments are data; arbitrary Lua and mutating methods are rejected.",
    )
    putJsonObject("items") {
        put("type", "object")
        put("additionalProperties", false)
        putJsonObject("properties") {
            putJsonObject("property") { put("type", "string") }
            putJsonObject("index") {
                putJsonArray("type") {
                    add("integer")
                    add("string")
                }
            }
            putJsonObject("method") {
                put("type", "string")
                putJsonArray("enum") { inspectionMethods.sorted().forEach { add(it) } }
            }
            putJsonObject("arguments") {
                put("type", "array")
                put("maxItems", 8)
                put(
                    "description",
                    "Method only: positional arguments matching the discovered API signature. Table-style methods take one JSON object. Omit for no arguments.",
                )
            }
            putJsonObject("result") {
                put("type", "integer")
                put("minimum", 1)
                put("maximum", 8)
                put("default", 1)
                put(
                    "description",
                    "Method only: one-based return value, e.g. get_recipe returns recipe first and quality second.",
                )
            }
        }
        putJsonArray("oneOf") {
            for (key in listOf("property", "index", "method")) addJsonObject {
                putJsonArray("required") { add(key) }
            }
        }
    }
}
