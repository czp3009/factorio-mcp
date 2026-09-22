package com.hiczp.factorio.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal suspend fun GameProcess.inspectEntity(args: JsonObject, timeoutMillis: Int): String {
    val position = queryPosition(args, true)
    val view = args["view"]?.jsonPrimitive?.content ?: "overview"
    require(view in setOf("overview", "configuration")) { "view must be overview or configuration" }
    val page = QueryPage(args)
    val details = if (view == "configuration") entityConfigurationQuerySource else """
        local recipe,quality,crafting
        if entity.type=='assembling-machine' or entity.type=='furnace' then
            recipe,quality=entity.get_recipe()
            crafting={active=entity.is_crafting(),progress=entity.crafting_progress,bonus_progress=entity.bonus_progress}
        end
        local inventories={}
        -- Inventory constants alias each other across entity types. Enumerate actual indices once.
        for id=1,entity.get_max_inventory_index() do
            local inventory=entity.get_inventory(id)
            if inventory then
                local all=inventory.get_contents()
                table.sort(all,function(a,b) return a.name==b.name and a.quality<b.quality or a.name<b.name end)
                local items={}
                for i=${page.offset}+1,math.min(#all,${page.end}) do items[#items+1]=all[i] end
                inventories[#inventories+1]={index=id,slots=#inventory,total=#all,offset=${page.offset},items=items,
                    item_count=inventory.get_item_count(),bar=inventory.supports_bar() and inventory.get_bar()-1 or nil}
            end
        end
        local statuses={}
        for name,value in pairs(defines.entity_status) do statuses[value]=name end
        local rocket
        if entity.type=='rocket-silo' then
            local states={}
            for name,value in pairs(defines.rocket_silo_status) do states[value]=name end
            rocket={status=states[entity.rocket_silo_status],parts=entity.rocket_parts,required_parts=entity.prototype.rocket_parts_required}
        end
        local burner=entity.burner
        local proxy=entity.item_request_proxy
        local fluidboxes={}
        for index=1,#entity.fluidbox do
            local fluid=entity.fluidbox[index]
            if fluid then fluidboxes[#fluidboxes+1]={index=index,fluid=fluid} end
        end
        return {tick=game.tick,name=entity.name,type=entity.type,unit_number=entity.unit_number,
            position=entity.position,direction=entity.direction,health=entity.health,status=statuses[entity.status],
            force=entity.force.name,reachable=p.can_reach_entity(entity),recipe=recipe and recipe.name or nil,
            recipe_quality=quality and quality.name or nil,crafting=crafting,inventories=inventories,
            fluids=entity.get_fluid_contents(),energy=entity.energy,rocket=rocket,
            fluidboxes=fluidboxes,temperature=entity.temperature,
            item_requests=proxy and proxy.insert_plan or {},
            burner=burner and {currently_burning=burner.currently_burning and {name=burner.currently_burning.name.name,quality=burner.currently_burning.quality.name} or nil,
                remaining_burning_fuel=burner.remaining_burning_fuel} or nil,
            inserter=entity.type=='inserter' and {pickup_position=entity.pickup_position,drop_position=entity.drop_position} or nil,
            amount=entity.type=='resource' and entity.amount or nil}
    """
    val result = executeOnTick(
        """
        local p=assert(__factorio_mcp_resident_v1).player()
        ${entityAtPositionSource(position)}
        $details
    """.trimIndent(), timeoutMillis
    ).jsonObject
    if (view == "configuration") {
        val settings = result.getValue("settings").jsonObject
        val sections = settings["logistic_sections"]
        if (sections != null) {
            val normalized = JsonArray(sections.luaArray().map { it.jsonObject.withLuaArrays("filters") })
            return JsonObject(result + ("settings" to JsonObject(settings + ("logistic_sections" to normalized)))).toString()
        }
        return result.toString()
    }
    return JsonObject(
        result.withLuaArrays("item_requests", "fluidboxes") + ("inventories" to
            JsonArray(result.getValue("inventories").luaArray().map { it.jsonObject.withLuaArrays("items") }))
    ).toString()
}
