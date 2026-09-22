package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*

internal class QueryPage(args: JsonObject) {
    val limit = args["limit"]?.jsonPrimitive?.int ?: 50
    val offset = args["offset"]?.jsonPrimitive?.int ?: 0

    init {
        require(limit in 1..128 && offset in 0..Int.MAX_VALUE - 128) { "Invalid query pagination" }
    }

    val end: Int get() = offset + limit
}

internal fun queryPosition(args: JsonObject, required: Boolean = false): String {
    val x = args["x"]?.jsonPrimitive?.double
    val y = args["y"]?.jsonPrimitive?.double
    require((x == null) == (y == null) && (x == null || x.isFinite() && y!!.isFinite())) { "Specify two finite coordinates" }
    require(!required || x != null) { "Coordinates are required" }
    return if (x == null) "p.position" else "{x=$x,y=$y}"
}

/** Factorio's JSON helper encodes an empty Lua table as an object, even for API arrays. */
internal fun JsonElement.luaArray(): JsonArray = when (this) {
    is JsonArray -> this
    is JsonObject -> {
        check(isEmpty()) { "Expected a Lua array" }; JsonArray(emptyList())
    }

    else -> error("Expected a Lua array")
}

internal fun JsonObject.withLuaArrays(vararg fields: String): JsonObject = JsonObject(mapValues { (key, value) ->
    if (key in fields) value.luaArray() else value
})
