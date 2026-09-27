package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*

internal fun validatePlayerSelector(value: JsonElement) {
    val player = value as? JsonPrimitive ?: error("player must be a name or positive index")
    require(
        if (player.isString)
            player.content.isNotBlank() &&
                    player.content.length <= 256 &&
                    '\u0000' !in player.content
        else player.intOrNull?.let { it > 0 } == true
    ) {
        "player must be a name or positive index"
    }
}

internal fun playerSelectorSchema() = buildJsonObject {
    putJsonArray("type") {
        add("string")
        add("integer")
    }
    put(
        "description",
        "Player name or positive API index in the current world. Omit for the attached client's local player. Available for player/character/vehicle/physical_vehicle/force and inventory owners.",
    )
}

internal fun parsePlayersQuery(args: JsonObject): WorldQuery {
    require(args.keys.all { it in setOf("selection", "fields", "offset", "limit") }) {
        "Unknown players query argument"
    }
    val selection = args.getValue("selection").jsonObject
    require(selection.keys.all { it in setOf("kind", "connected", "names", "indices") }) {
        "Unknown players selector"
    }
    selection["connected"]?.booleanArgument()
    for (key in listOf("names", "indices")) selection[key]?.let { value ->
        val values = value.jsonArray
        val maximum = if (key == "names") 64 else 128
        require(values.size in 1..maximum && values.distinct().size == values.size) {
            "$key requires 1..$maximum distinct entries"
        }
        values.forEach {
            validatePlayerSelector(it)
            require(it.jsonPrimitive.isString == (key == "names")) { "Invalid $key entry" }
        }
    }
    val fields =
        args["fields"]?.jsonArray?.map { it.stringArgument() }
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
    require(
        fields.size in 1..32 &&
                fields.distinct().size == fields.size &&
                fields.all { it in playerFields }
    ) {
        "Unsupported player fields: choose from ${playerFields.joinToString()}"
    }
    val limit = args["limit"]?.intArgument() ?: 64
    val offset = args["offset"]?.intArgument() ?: 0
    require(limit in 1..128 && offset in 0..65536) {
        "Players limit must be 1..128 and offset 0..65536"
    }
    return WorldQuery(
        buildJsonObject {
            args.forEach { (name, value) -> put(name, value) }
            putJsonArray("fields") { fields.forEach { add(it) } }
            put("limit", limit)
            put("offset", offset)
        }
    )
}
