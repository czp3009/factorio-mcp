package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*

internal suspend fun GameProcess.readPlayer(args: JsonObject, timeoutMillis: Int): String {
    val section = args["section"]?.jsonPrimitive?.content ?: "status"
    require(
        section in setOf(
            "status",
            "cursor",
            "crafting",
            "research",
            "quickbar",
            "equipment"
        )
    ) { "Unknown player section" }
    return readSnapshot(if (section == "status") "player" else section, args, timeoutMillis)
}

internal suspend fun GameProcess.readPrototypes(args: JsonObject, timeoutMillis: Int): String {
    val kind = args.getValue("kind").jsonPrimitive.content
    require(kind in setOf("items", "entities", "fluids")) { "kind must be items, entities or fluids" }
    return readSnapshot(if (kind == "entities") "entity_prototypes" else kind, args, timeoutMillis)
}

/** Fixed read-only snapshots, not an arbitrary Lua execution endpoint. */
private suspend fun GameProcess.readSnapshot(kind: String, request: JsonObject, timeoutMillis: Int): String {
    val page = QueryPage(request)
    val limit = page.limit
    val offset = page.offset
    val name = request["name"]?.jsonPrimitive?.also { require(it.isString) }?.content
    val localPlayer = "assert(__factorio_mcp_resident_v1).player()"
    val body = when (kind) {
        "player" -> """
            local p = $localPlayer
            local cursor = p.cursor_stack
            local cargoPod=p.character and p.character.cargo_pod
            return {tick=game.tick, index=p.index, name=p.name, connected=p.connected, admin=p.admin, reach_distance=p.reach_distance, build_distance=p.build_distance,
              position=p.position, controller_type=p.controller_type, physical_position=p.physical_position,physical_surface=p.physical_surface.name,physical_controller_type=p.physical_controller_type,render_mode=p.render_mode,zoom=p.zoom,has_character=p.character~=nil, in_cargo_pod=cargoPod~=nil, selected_gun_index=p.character and p.character.selected_gun_index or nil, selected=p.selected and {name=p.selected.name,position=p.selected.position} or nil, surface=p.surface.name, opened_gui_type=p.opened_gui_type, display_resolution=p.display_resolution, display_scale=p.display_scale, walking=p.walking_state, mining=p.mining_state, riding=p.driving and p.riding_state or nil, vehicle=p.vehicle and {name=p.vehicle.name,position=p.vehicle.position,speed=p.vehicle.speed,orientation=p.vehicle.orientation} or nil,
              cursor=cursor and cursor.valid_for_read and {name=cursor.name, count=cursor.count} or {},
              research=p.force.current_research and p.force.current_research.name or nil}
        """

        "crafting" -> """
            local p = $localPlayer
            local queue, items = p.crafting_queue or {}, {}
            for i=$offset+1, math.min(#queue, $offset+$limit) do items[#items+1]=queue[i] end
            return {tick=game.tick, total=#queue, offset=$offset, progress=p.crafting_queue_progress, items=items}
        """

        "cursor" -> """
            local p = $localPlayer
            local stack, ghost = p.cursor_stack, p.cursor_ghost
            return {tick=game.tick, hand_location=p.hand_location,
                stack=stack and stack.valid_for_read and {name=stack.name, count=stack.count, quality=stack.quality.name} or nil,
                ghost=ghost and {name=ghost.name.name, quality=ghost.quality.name} or nil}
        """

        "research" -> """
            local p = $localPlayer
            local force, queue = p.force, {}
            for _, technology in pairs(force.research_queue or {}) do
                queue[#queue+1] = type(technology)=='string' and technology or technology.name
            end
            return {tick=game.tick, force=force.name, current=force.current_research and force.current_research.name or nil,
                progress=force.research_progress, queue=queue}
        """

        "quickbar" -> """
            local p = $localPlayer
            local slots = {}
            for i=$offset+1, $offset+$limit do
                local ok, filter = pcall(p.get_quick_bar_slot, i)
                if not ok then break end
                slots[#slots+1] = {slot=i, filter=filter}
            end
            return {tick=game.tick, offset=$offset, active_page=p.get_active_quick_bar_page(1), slots=slots}
        """

        "equipment" -> """
            local p = $localPlayer
            local inventory = p.get_inventory(defines.inventory.character_armor)
            local items = {}
            if inventory then
                for slot=1,#inventory do
                    local stack = inventory[slot]
                    local grid = stack.valid_for_read and stack.grid
                    if grid then
                        local equipment = {}
                        for _, e in pairs(grid.equipment) do
                            equipment[#equipment+1] = {name=e.name, position=e.position, quality=e.quality.name, energy=e.energy,
                                shield=e.shield, max_shield=e.max_shield}
                        end
                        items[#items+1] = {slot=slot, name=stack.name, width=grid.width, height=grid.height, equipment=equipment}
                    end
                end
            end
            return {tick=game.tick, items=items}
        """

        "items", "entity_prototypes", "fluids" -> {
            val collection = when (kind) {
                "items" -> "item"
                "fluids" -> "fluid"
                else -> "entity"
            }
            val describe = when (kind) {
                "items" -> "{name=n,type=prototype.type,stack_size=prototype.stack_size,fuel_value=prototype.fuel_value,spoil_result=prototype.spoil_result and prototype.spoil_result.name or nil,place_result=prototype.place_result and prototype.place_result.name or nil,plant_result=prototype.plant_result and prototype.plant_result.name or nil,place_as_tile=prototype.place_as_tile_result and prototype.place_as_tile_result.result.name or nil}"
                "fluids" -> "{name=n,default_temperature=prototype.default_temperature,max_temperature=prototype.max_temperature,fuel_value=prototype.fuel_value}"
                else -> "{name=n,type=prototype.type,items_to_place_this=prototype.items_to_place_this,collision_box=prototype.collision_box,selection_box=prototype.selection_box,crafting_categories=prototype.crafting_categories,mineable_properties=prototype.mineable_properties}"
            }
            """
                local all,names,items=prototypes.$collection,{},{}
                ${if (name != null) "names[1]=${luaQuote(name)}" else "for n in pairs(all) do names[#names+1]=n end;table.sort(names)"}
                for i=$offset+1,math.min(#names,$offset+$limit) do
                    local n=names[i]
                    local prototype=assert(all[n], 'prototype not found')
                    items[#items+1]=$describe
                end
                return {tick=game.tick,total=#names,offset=$offset,items=items}
            """
        }

        else -> error("Unknown query kind: $kind")
    }
    val result = executeOnTick(body.trimIndent(), timeoutMillis).jsonObject
    return when (kind) {
        "crafting", "equipment", "items", "entity_prototypes", "fluids" -> result.withLuaArrays("items")
        "quickbar" -> result.withLuaArrays("slots")
        "research" -> result.withLuaArrays("queue")
        else -> result
    }.toString()
}

internal suspend fun GameProcess.executeOnTick(source: String, timeoutMillis: Int): JsonElement {
    val description = """
        return {callback=function()
            $source
        end}
    """.trimIndent()
    return Json.parseToJsonElement(submitTask(description, timeoutMillis)).jsonObject["value"] ?: JsonNull
}
