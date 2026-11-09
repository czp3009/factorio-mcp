package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*

internal fun validatePlayerSelector(value: JsonElement) {
    val player = value as? JsonPrimitive ?: error("player must be a name or positive index")
    require(
        if (player.isString)
            player.content.isNotEmpty() &&
                    '\u0000' !in player.content
        else player.longOrNull?.let { it in 1..4294967295L } == true
    ) {
        "player must be a name or positive index"
    }
}

internal fun parsePlayersQuery(args: JsonObject): WorldQuery {
    require(args.keys.all { it in setOf("selection", "fields", "offset", "limit", "depth", "reads") }) {
        "Unknown players query argument"
    }
    val selection = args.getValue("selection").jsonObject
    require(selection.keys.all { it in setOf("kind", "connected", "names", "indices") }) {
        "Unknown players selector"
    }
    selection["connected"]?.booleanArgument()
    for (key in listOf("names", "indices")) selection[key]?.let { value ->
        val values = value.jsonArray
        require(values.isNotEmpty() && values.distinct().size == values.size) {
            "$key requires distinct nonempty entries"
        }
        values.forEach {
            validatePlayerSelector(it)
            require(it.jsonPrimitive.isString == (key == "names")) { "Invalid $key entry" }
        }
    }
    val fields =
        args["fields"]?.let(::nativeFields)
            ?: listOf(
                "index",
                "name",
                "connected",
                "force",
                "controller_type",
                "position",
                "surface",
                "physical_position",
                "physical_surface",
                "character",
            )
    val limit = args["limit"]?.intArgument()
    val offset = args["offset"]?.intArgument() ?: 0
    require((limit == null || limit > 0) && offset >= 0) {
        "Players limit must be positive and offset nonnegative"
    }
    return WorldQuery(
        buildJsonObject {
            args.forEach { (name, value) -> put(name, value) }
            putJsonArray("fields") { fields.forEach { add(it) } }
            limit?.let { put("limit", it) }
            put("offset", offset)
            put("depth", observationDepth(args["depth"]?.intArgument()))
            args["reads"]?.let { put("reads", observationReads(it)) }
        }
    )
}
