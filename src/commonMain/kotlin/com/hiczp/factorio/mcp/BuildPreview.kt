package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*

/** A preview reads placement rules; it never creates items, ghosts, or entities. */
internal suspend fun GameProcess.previewBuild(args: JsonObject, timeoutMillis: Int): String {
    val placements = args.getValue("placements").jsonArray
    require(placements.size in 1..128) { "Provide 1..128 placements" }
    for (value in placements) {
        val placement = value.jsonObject
        require(placement.keys.all {
            it in setOf(
                "name",
                "x",
                "y",
                "direction",
                "item",
                "quality"
            )
        }) { "Unknown placement field" }
        require(placement.getValue("name").jsonPrimitive.content.isNotBlank()) { "An entity prototype name is required" }
        queryPosition(placement, true)
        require(
            (placement["direction"]?.jsonPrimitive?.content ?: "north") in setOf(
                "north",
                "east",
                "south",
                "west"
            )
        ) { "Use a cardinal direction name" }
    }
    val snapshot = executeOnTick(
        """
        local p=assert(__factorio_mcp_resident_v1).player()
        local placements=assert(helpers.json_to_table(${luaQuote(placements.toString())}))
        local inventory=assert(p.get_main_inventory(), 'a character inventory is required')
        local items={}
        for i,placement in ipairs(placements) do
            local prototype=assert(prototypes.entity[placement.name], 'entity prototype not found')
            local quality=placement.quality or 'normal'
            assert(prototypes.quality[quality], 'quality not found')
            local position={x=placement.x,y=placement.y}
            assert(p.force.is_chunk_charted(p.surface,{math.floor(position.x/32),math.floor(position.y/32)}), 'position is not charted')
            local alternatives={}
            for _,item in pairs(prototype.items_to_place_this or {}) do
                if not placement.item or item.name==placement.item then
                    alternatives[#alternatives+1]={name=item.name,count=item.count,quality=quality,
                        available=inventory.get_item_count{name=item.name,quality=quality}}
                end
            end
            if placement.item then assert(#alternatives>0, 'item cannot place this entity') end
            items[#items+1]={index=i,name=prototype.name,position=position,direction=placement.direction or 'north',
                can_place=p.can_place_entity{name=prototype.name,position=position,direction=defines.direction[placement.direction or 'north']},
                alternatives=alternatives}
        end
        return {tick=game.tick,surface=p.surface.name,placements=items}
    """.trimIndent(), timeoutMillis
    ).jsonObject
    val requirements = mutableMapOf<Pair<String, String>, Pair<Double, Double>>()
    var unresolved = 0
    for (value in snapshot.getValue("placements").jsonArray) {
        val alternatives = value.jsonObject.getValue("alternatives").luaArray()
        if (alternatives.size != 1) {
            unresolved++
            continue
        }
        val item = alternatives.single().jsonObject
        val key = item.getValue("name").jsonPrimitive.content to item.getValue("quality").jsonPrimitive.content
        val required = (requirements[key]?.first ?: 0.0) + item.getValue("count").jsonPrimitive.double
        requirements[key] = required to item.getValue("available").jsonPrimitive.double
    }
    return buildJsonObject {
        for ((key, value) in snapshot) put(key, value)
        put("placements", JsonArray(snapshot.getValue("placements").luaArray().map {
            it.jsonObject.withLuaArrays("alternatives")
        }))
        put("unresolved_material_choices", unresolved)
        putJsonArray("materials") {
            for ((key, counts) in requirements.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))) {
                add(buildJsonObject {
                    put("name", key.first)
                    put("quality", key.second)
                    put("required", counts.first)
                    put("available", counts.second)
                    put("shortfall", maxOf(0.0, counts.first - counts.second))
                })
            }
        }
        put(
            "scope",
            "Each placement is checked independently against the current world using the player placement API. Does not simulate collisions between proposed placements, reserve materials, or guarantee later success. Choose item explicitly when alternatives are ambiguous."
        )
    }.toString()
}
