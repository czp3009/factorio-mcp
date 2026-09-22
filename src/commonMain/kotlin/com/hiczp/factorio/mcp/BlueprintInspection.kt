package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*

/** Decode through the game's codec; analyze the bounded result in MCP without allocating an item stack. */
internal suspend fun GameProcess.inspectBlueprint(args: JsonObject, timeoutMillis: Int): String {
    val encoded = args["string"]?.jsonPrimitive?.content
    val data = args["data"]?.jsonObject
    require(encoded == null || data == null) { "Specify string or data, or neither to inspect the held blueprint" }
    require((encoded ?: data?.toString() ?: "").encodeToByteArray().size <= 24000) { "Blueprint input is too large" }
    val includeString = args["include_string"]?.jsonPrimitive?.boolean ?: false
    val includeData = args["include_data"]?.jsonPrimitive?.boolean ?: false
    val snapshot = executeOnTick(
        """
        local blueprint
        ${
            when {
                encoded != null -> """
                local encoded=${luaQuote(encoded)}
                assert(encoded:sub(1,1)=='0', 'unsupported blueprint string format')
                local decoded=assert(helpers.decode_string(encoded:sub(2)), 'invalid blueprint encoding')
                assert(#decoded<=262144, 'decoded blueprint is too large')
                local document=assert(helpers.json_to_table(decoded), 'invalid blueprint JSON')
                blueprint=assert(document.blueprint, 'a single blueprint is required')
            """

                data != null -> "blueprint=assert(helpers.json_to_table(${luaQuote(data.toString())}), 'invalid blueprint data')"
                else -> """
                local p=assert(__factorio_mcp_resident_v1).player()
                local stack=p.cursor_stack
                assert(stack and stack.valid_for_read and stack.is_blueprint and stack.is_blueprint_setup(), 'hold a configured blueprint first')
                local encoded=stack.export_stack()
                local decoded=assert(helpers.decode_string(encoded:sub(2)), 'invalid held blueprint')
                assert(#decoded<=262144, 'decoded blueprint is too large')
                blueprint=assert(helpers.json_to_table(decoded)).blueprint
            """
            }
        }
        local entities,tiles=blueprint.entities or {},blueprint.tiles or {}
        assert(#entities+#tiles>0 and #entities+#tiles<=128, 'blueprints must contain between 1 and 128 entities/tiles')
        local placementItems={}
        for _,entity in ipairs(entities) do
            local prototype=assert(prototypes.entity[entity.name], 'unknown entity prototype')
            placementItems[entity.name]=prototype.items_to_place_this or {}
        end
        for _,tile in ipairs(tiles) do assert(prototypes.tile[tile.name], 'unknown tile prototype') end
        $blueprintEncodingSource
        return {tick=game.tick,data=blueprint,placement_items=placementItems,string=${if (includeString) "encoded" else "nil"}}
    """.trimIndent(), timeoutMillis
    ).jsonObject
    val blueprint = snapshot.getValue("data").jsonObject
    val entities = blueprint["entities"]?.luaArray() ?: JsonArray(emptyList())
    val tiles = blueprint["tiles"]?.luaArray() ?: JsonArray(emptyList())
    return buildJsonObject {
        put("tick", snapshot.getValue("tick"))
        put("entity_count", entities.size)
        put("tile_count", tiles.size)
        blueprint["label"]?.let { put("label", it) }
        putJsonArray("summary") {
            for ((key, entries) in entities.groupBy {
                it.jsonObject.getValue("name").jsonPrimitive.content to (it.jsonObject["quality"]?.jsonPrimitive?.content
                    ?: "normal")
            }.toList().sortedWith(compareBy({ it.first.first }, { it.first.second }))) {
                add(buildJsonObject {
                    put("name", key.first)
                    put("quality", key.second)
                    put("count", entries.size)
                    put(
                        "placement_items",
                        snapshot.getValue("placement_items").jsonObject.getValue(key.first).luaArray()
                    )
                })
            }
        }
        put(
            "scope",
            "Entity counts grouped by prototype and quality, with per-entity alternative placement items. Tiles are counted separately. No flow, throughput, collision or buildability inference."
        )
        if (includeData) put("data", JsonObject(blueprint + mapOf("entities" to entities, "tiles" to tiles)))
        snapshot["string"]?.let { put("string", it) }
    }.toString()
}
