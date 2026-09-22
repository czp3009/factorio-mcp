package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*

/** One bounded game snapshot; sorting, pagination and diagnosis summaries belong to MCP. */
internal suspend fun GameProcess.inspectArea(args: JsonObject, timeoutMillis: Int): String {
    val page = QueryPage(args)
    val position = queryPosition(args)
    val radius = args["radius"]?.jsonPrimitive?.int ?: 16
    require(radius in 1..64) { "radius must be 1..64" }
    val mode = args["mode"]?.jsonPrimitive?.content ?: "entities"
    if (mode == "tiles") return inspectTiles(args, timeoutMillis)
    if (mode == "visibility") return inspectVisibility(args, timeoutMillis)
    require(mode in setOf("entities", "resources", "production", "ghosts")) { "Unknown area mode" }
    val type = args["type"]?.jsonPrimitive?.content
    val relation = args["relation"]?.jsonPrimitive?.content ?: "any"
    require(relation in setOf("any", "own", "hostile")) { "relation must be any, own or hostile" }
    require(type == null || mode == "entities") { "type is only supported in entities mode" }
    val name = args["name"]?.jsonPrimitive?.content
    val status = args["status"]?.jsonPrimitive?.content
    val snapshot = executeOnTick(
        """
        local p=assert(__factorio_mcp_resident_v1).player()
        local position=$position
        ${status?.let { "assert(defines.entity_status[${luaQuote(it)}], 'unknown entity status')" } ?: ""}
        local filter={position=position,radius=$radius,limit=513}
        ${name?.let { "filter.name=${luaQuote(it)};assert(prototypes.entity[filter.name], 'entity prototype not found')" } ?: ""}
        ${if (mode == "resources") "filter.type='resource'" else ""}
        ${if (mode == "production") "filter.force=p.force;filter.type={'assembling-machine','furnace','mining-drill','lab','boiler','generator','reactor','inserter'}" else ""}
        ${if (mode == "ghosts") "filter.type={'entity-ghost','tile-ghost'}" else ""}
        ${type?.let { "filter.type=${luaQuote(it)}" } ?: ""}
        ${if (relation == "own") "filter.force=p.force" else ""}
        local entities=p.surface.find_entities_filtered(filter)
        local statusNames={}
        for n,value in pairs(defines.entity_status) do statusNames[value]=n end
        local items={}
        for i=1,math.min(#entities,512) do
            local e=entities[i]
            -- Chunk coordinates use the game's documented 32-tile chunk size.
            if p.force.is_chunk_charted(p.surface,{math.floor(e.position.x/32),math.floor(e.position.y/32)})
                and (${if (relation == "hostile") "not p.force.is_friend(e.force) and not p.force.get_cease_fire(e.force)" else "true"}) then
                local recipe
                if e.type=='assembling-machine' or e.type=='furnace' then recipe=e.get_recipe() end
                items[#items+1]={name=e.name,type=e.type,position=e.position,unit_number=e.unit_number,
                    force=e.force.name,status=statusNames[e.status],recipe=recipe and recipe.name or nil,
                    ghost_name=(e.type=='entity-ghost' or e.type=='tile-ghost') and e.ghost_name or nil,
                    military_target=e.is_military_target,
                    reachable=p.can_reach_entity(e),amount=e.type=='resource' and e.amount or nil}
            end
        end
        return {tick=game.tick,surface=p.surface.name,center=position,scan_truncated=#entities>512,items=items}
    """.trimIndent(), timeoutMillis).jsonObject
    val center = snapshot.getValue("center").jsonObject
    val centerX = center.getValue("x").jsonPrimitive.double
    val centerY = center.getValue("y").jsonPrimitive.double
    val items = snapshot.getValue("items").luaArray().map { it.jsonObject }.filter {
        status == null || it["status"]?.jsonPrimitive?.content == status
    }.sortedWith(compareBy<JsonObject> {
        val point = it.getValue("position").jsonObject
        val dx = point.getValue("x").jsonPrimitive.double - centerX
        val dy = point.getValue("y").jsonPrimitive.double - centerY
        dx * dx + dy * dy
    }.thenBy { it.getValue("name").jsonPrimitive.content }
        .thenBy { it.getValue("position").jsonObject.getValue("x").jsonPrimitive.double }
        .thenBy { it.getValue("position").jsonObject.getValue("y").jsonPrimitive.double })
    return buildJsonObject {
        for ((key, value) in snapshot) if (key != "items") put(key, value)
        put("mode", mode)
        put("radius", radius)
        put("total_in_scan", items.size)
        put("offset", page.offset)
        put("items", JsonArray(items.drop(page.offset).take(page.limit)))
        putJsonArray("summary") {
            for ((name, group) in items.groupBy { it.getValue("name").jsonPrimitive.content }.toList()
                .sortedBy { it.first }) {
                add(buildJsonObject {
                    put("name", name)
                    put("count", group.size)
                    if (mode == "resources") put("amount", group.sumOf { it.getValue("amount").jsonPrimitive.double })
                    putJsonObject("statuses") {
                        for ((state, entries) in group.groupBy { it["status"]?.jsonPrimitive?.content ?: "unspecified" }
                            .toList().sortedBy { it.first }) put(state, entries.size)
                    }
                })
            }
        }
        put(
            "scope",
            "Charted chunks on the local surface; at most 512 matching entities. Sorted by distance within the scanned set; summaries are partial when scan_truncated is true."
        )
    }.toString()
}

private suspend fun GameProcess.inspectVisibility(args: JsonObject, timeoutMillis: Int): String {
    require(args.keys.none {
        it in setOf(
            "type",
            "status",
            "relation",
            "name"
        )
    }) { "Visibility inspection does not accept entity filters" }
    val page = QueryPage(args)
    val radius = args["radius"]?.jsonPrimitive?.int ?: 16
    return executeOnTick(
        """
        local p=assert(__factorio_mcp_resident_v1).player()
        local position=${queryPosition(args)}
        local chunks={}
        for y=math.floor((position.y-$radius)/32),math.floor((position.y+$radius)/32) do
            for x=math.floor((position.x-$radius)/32),math.floor((position.x+$radius)/32) do
                local chunk={x=x,y=y}
                chunks[#chunks+1]={position=chunk,charted=p.force.is_chunk_charted(p.surface,chunk),
                    visible=p.force.is_chunk_visible(p.surface,chunk)}
            end
        end
        local items={}
        for i=${page.offset}+1,math.min(#chunks,${page.end}) do items[#items+1]=chunks[i] end
        return {tick=game.tick,surface=p.surface.name,center=position,mode='visibility',radius=$radius,
            total=#chunks,offset=${page.offset},items=items,scope='All 32-tile chunks intersecting the requested square. Charted is explored; visible is currently revealed. Neither alone guarantees an action will be accepted.'}
    """.trimIndent(), timeoutMillis
    ).jsonObject.withLuaArrays("items").toString()
}

internal suspend fun GameProcess.inspectNetworks(args: JsonObject, timeoutMillis: Int): String {
    val position = queryPosition(args)
    val page = QueryPage(args)
    val snapshot = executeOnTick(
        """
        local p=assert(__factorio_mcp_resident_v1).player()
        local position=$position
        assert(p.force.is_chunk_charted(p.surface,{math.floor(position.x/32),math.floor(position.y/32)}), 'position is not charted')
        local entity,t
        for _,e in pairs(p.surface.find_entities_filtered{position=position,force=p.force,limit=32}) do
            if e.electric_buffer_size or e.type=='electric-pole' then entity=e end
            if e.train then t=e.train end
        end
        local electric,logistic,train
        if entity and entity.electric_buffer_size then
            electric={entity=entity.name,network_id=entity.electric_network_id,
                connected_to_generator=entity.is_connected_to_electric_network(),
                buffer_energy=entity.energy,buffer_capacity=entity.electric_buffer_size}
        elseif entity and entity.type=='electric-pole' then
            electric={entity=entity.name,network_id=entity.electric_network_id}
        end
        local network=p.surface.find_logistic_network_by_position(position,p.force)
        if network then
            local contents=network.get_contents()
            table.sort(contents,function(a,b) return a.name==b.name and a.quality<b.quality or a.name<b.name end)
            local items={}
            for i=${page.offset}+1,math.min(#contents,${page.end}) do items[#items+1]=contents[i] end
            logistic={logistic_robots=network.all_logistic_robots,available_logistic_robots=network.available_logistic_robots,
                construction_robots=network.all_construction_robots,available_construction_robots=network.available_construction_robots,
                total_items=#contents,offset=${page.offset},items=items}
        end
        if t then
            local stateNames={}
            for name,value in pairs(defines.train_state) do stateNames[value]=name end
            local schedule=t.schedule
            local records={}
            local all=schedule and schedule.records or {}
            for i=${page.offset}+1,math.min(#all,${page.end}) do
                local record=all[i]
                records[#records+1]={station=record.station,temporary=record.temporary,
                    rail=record.rail and record.rail.valid and record.rail.position or nil,wait_conditions=record.wait_conditions}
            end
            train={id=t.id,state=stateNames[t.state],manual_mode=t.manual_mode,speed=t.speed,
                station=t.station and t.station.backer_name or nil,schedule_current=schedule and schedule.current or nil,
                total_records=#all,offset=${page.offset},records=records,schedule_scope='Basic schedule; excludes interrupts and groups'}
        end
        return {tick=game.tick,surface=p.surface.name,position=position,electric=electric or false,
            logistic=logistic or false,train=train or false}
    """.trimIndent(), timeoutMillis
    ).jsonObject
    return buildJsonObject {
        for ((key, value) in snapshot) put(
            key, when {
                key == "logistic" && value is JsonObject -> value.withLuaArrays("items")
                key == "train" && value is JsonObject -> value.withLuaArrays("records")
                else -> value
            }
        )
    }.toString()
}

private suspend fun GameProcess.inspectTiles(args: JsonObject, timeoutMillis: Int): String {
    require(args.keys.none {
        it in setOf(
            "type",
            "status",
            "relation"
        )
    }) { "Tile inspection does not accept entity filters" }
    val page = QueryPage(args)
    val radius = args["radius"]?.jsonPrimitive?.int ?: 16
    val name = args["name"]?.jsonPrimitive?.content
    val result = executeOnTick(
        """
        local p=assert(__factorio_mcp_resident_v1).player()
        local position=${queryPosition(args)}
        local all=p.surface.find_tiles_filtered{position=position,radius=$radius,limit=513,
            name=${name?.let(::luaQuote) ?: "nil"}}
        local items={}
        for i=1,math.min(#all,512) do
            local tile=all[i]
            if p.force.is_chunk_charted(p.surface,{math.floor(tile.position.x/32),math.floor(tile.position.y/32)}) then
                items[#items+1]={name=tile.name,position=tile.position,collides_with_player=tile.collides_with('player')}
            end
        end
        return {tick=game.tick,surface=p.surface.name,center=position,scan_truncated=#all>512,items=items}
    """.trimIndent(), timeoutMillis
    ).jsonObject
    val center = result.getValue("center").jsonObject
    val items = result.getValue("items").luaArray().sortedBy {
        val point = it.jsonObject.getValue("position").jsonObject
        val dx = point.getValue("x").jsonPrimitive.double - center.getValue("x").jsonPrimitive.double
        val dy = point.getValue("y").jsonPrimitive.double - center.getValue("y").jsonPrimitive.double
        dx * dx + dy * dy
    }
    return buildJsonObject {
        for ((key, value) in result) if (key != "items") put(key, value)
        put("mode", "tiles")
        put("total_in_scan", items.size)
        put("offset", page.offset)
        put("items", JsonArray(items.drop(page.offset).take(page.limit)))
        put("scope", "Charted tiles from at most 512 matches; distance ordering is within the scanned set.")
    }.toString()
}
