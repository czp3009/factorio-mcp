package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*

internal fun inspectionPathSchema() = buildJsonObject {
    put("type", "array")
    put(
        "description",
        "Ordered relative traversal for inspect or per-root reads, reacquired in this observation. Select a readable property, a collection index/key, or an admitted query method. Use mode:members to discover names and signatures. Arguments are data; arbitrary Lua and mutating methods are rejected.",
    )
    putJsonObject("items") {
        put("type", "object")
        put("additionalProperties", false)
        putJsonObject("properties") {
            putJsonObject("property") {
                put("type", "string")
                put("pattern", "^[A-Za-z_][A-Za-z0-9_]*$")
            }
            putJsonObject("index") {
                putJsonArray("type") {
                    add("number")
                    add("string")
                }
            }
            putJsonObject("method") {
                put("type", "string")
                putJsonArray("enum") { inspectionMethods.sorted().forEach { add(it) } }
            }
            putJsonObject("arguments") {
                put("type", "array")
                put(
                    "description",
                    "Method only: positional arguments matching the discovered API signature. Table-style methods take one JSON object. Omit for no arguments.",
                )
            }
            putJsonObject("result") {
                put("type", "integer")
                put("minimum", 1)
                put("maximum", Int.MAX_VALUE)
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
                put("additionalProperties", false)
                putJsonObject("properties") {
                    put(key, buildJsonObject {})
                    if (key == "method") {
                        put("arguments", buildJsonObject {})
                        put("result", buildJsonObject {})
                    }
                }
            }
        }
    }
}
