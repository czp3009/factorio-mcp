package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*

internal fun validateEntityFilter(name: String, value: JsonElement) {
    val values = if (value is JsonArray) value else JsonArray(listOf(value))
    require(values.size in 1..64) { "$name requires 1..64 names" }
    val names = values.map { it.stringArgument() }
    require(
        names.distinct().size == names.size &&
                names.all { it.isNotBlank() && it.length <= 256 && '\u0000' !in it }
    ) {
        "Invalid or repeated $name filter"
    }
}

internal fun entityFilterSchema(name: String) = buildJsonObject {
    put(
        "description",
        if (name == "name")
            "Exact internal entity prototype name(s). Entries are OR; name and type filters combine with AND."
        else
            "Exact native entity type(s), e.g. transport-belt, underground-belt, splitter, mining-drill. Entries are OR; name and type filters combine with AND. Mod prototypes retain their native types; no inferred categories.",
    )
    putJsonArray("oneOf") {
        addJsonObject {
            put("type", "string")
            put("minLength", 1)
            put("maxLength", 256)
        }
        addJsonObject {
            put("type", "array")
            put("minItems", 1)
            put("maxItems", 64)
            put("uniqueItems", true)
            putJsonObject("items") {
                put("type", "string")
                put("minLength", 1)
                put("maxLength", 256)
            }
        }
    }
}
