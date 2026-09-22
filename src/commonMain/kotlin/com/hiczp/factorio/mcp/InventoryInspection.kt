package com.hiczp.factorio.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Inventory summaries preserve quality; slot details are fetched only when requested. */
internal suspend fun GameProcess.inspectInventory(args: JsonObject, timeoutMillis: Int): String {
    val page = QueryPage(args)
    val position = queryPosition(args)
    val entityInventory = "x" in args
    val name = args["name"]?.jsonPrimitive?.content ?: if (entityInventory) "chest" else "character_main"
    val index = args["index"]?.jsonPrimitive?.int
    require(index == null || entityInventory && index > 0 && "name" !in args) { "index requires an entity target and cannot be combined with name" }
    val view = args["view"]?.jsonPrimitive?.content ?: "summary"
    require(view in setOf("summary", "slots")) { "view must be summary or slots" }
    val result = executeOnTick(
        """
        local p=assert(__factorio_mcp_resident_v1).player()
        ${if (entityInventory) entityAtPositionSource(position) else "local entity=p"}
        local name=${luaQuote(name)}
        ${if (!entityInventory) "assert(name:match('^character_'), 'a character inventory name is required')" else ""}
        local id=${index?.toString() ?: "assert(defines.inventory[name], 'unknown inventory name')"}
        local inventory=assert(entity.get_inventory(id), 'inventory is unavailable')
        local items,filters={},{}
        local empty=0
        for i=1,#inventory do if not inventory[i].valid_for_read then empty=empty+1 end end
        ${
            if (view == "summary") """
        local all=inventory.get_contents()
        table.sort(all,function(a,b) return a.name==b.name and a.quality<b.quality or a.name<b.name end)
        for i=${page.offset}+1,math.min(#all,${page.end}) do items[#items+1]=all[i] end
        local total=#all
        """ else """
        for i=${page.offset}+1,math.min(#inventory,${page.end}) do
            if inventory.supports_filters() then
                local filter=inventory.get_filter(i)
                if filter then filters[#filters+1]={slot=i,filter=filter} end
            end
            local stack=inventory[i]
            if stack.valid_for_read then
                items[#items+1]={slot=i,name=stack.name,count=stack.count,quality=stack.quality.name,
                    spoil_percent=stack.spoil_percent,spoil_tick=stack.spoil_tick,
                    health=stack.health,durability=stack.is_tool and stack.durability or nil,
                    ammo=stack.is_ammo and stack.ammo or nil}
            end
        end
        local total=#inventory
        """
        }
        return {tick=game.tick,inventory=${if (index == null) "name" else "nil"},inventory_index=id,slots=#inventory,empty_slots=empty,
            item_count=inventory.get_item_count(),total=total,offset=${page.offset},items=items,filters=filters,
            inventory_bar=inventory.supports_bar() and inventory.get_bar()-1 or nil,
            entity=${if (entityInventory) "entity.name" else "nil"},position=entity.position,view=${luaQuote(view)}}
    """.trimIndent(), timeoutMillis
    ).jsonObject
    return result.withLuaArrays("items", "filters").toString()
}

/** Select a visible point target and never inspect another character's private state. */
internal fun entityAtPositionSource(position: String): String = """
    local position=$position
    assert(p.force.is_chunk_charted(p.surface,{math.floor(position.x/32),math.floor(position.y/32)}), 'position is not charted')
    local entity
    for _,candidate in pairs(p.surface.find_entities_filtered{position=position,limit=32}) do
        if not entity or candidate.type~='resource' then entity=candidate end
    end
    assert(entity, 'entity not found')
    assert(entity.type~='character' or entity.player==p, 'other character details are not exposed')
""".trimIndent()
