package com.hiczp.factorio.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Space topology is prototype data; platform state and unlocks are live force data. */
internal suspend fun GameProcess.inspectSpace(args: JsonObject, timeoutMillis: Int): String {
    val section = args["section"]?.jsonPrimitive?.content ?: "platforms"
    require(section in setOf("platforms", "locations", "connections", "surfaces")) { "Unknown space section" }
    val page = QueryPage(args)
    val name = args["name"]?.jsonPrimitive?.content
    val entries = when (section) {
        "locations" -> """
            for name,location in pairs(prototypes.space_location) do
                local planet=game.planets[name]
                entries[#entries+1]={name=name,unlocked=p.force.is_space_location_unlocked(name),
                    surface=planet and planet.surface and planet.surface.name or nil,
                    surface_properties=location.surface_properties,solar_power_in_space=location.solar_power_in_space,
                    entities_require_heating=location.entities_require_heating,fly_condition=location.fly_condition}
            end
        """

        "connections" -> """
            for name,connection in pairs(prototypes.space_connection) do
                entries[#entries+1]={name=name,from=connection.from.name,to=connection.to.name,length=connection.length}
            end
        """

        "surfaces" -> """
            for _,surface in pairs(game.surfaces) do
                local platform=surface.platform
                if not p.force.get_surface_hidden(surface) and (not platform or platform.force==p.force and not platform.hidden) then
                    entries[#entries+1]={name=surface.name,index=surface.index,planet=surface.planet and surface.planet.name or nil,
                        platform=platform and platform.index or nil}
                end
            end
        """

        else -> """
            local states={}
            for name,value in pairs(defines.space_platform_state) do states[value]=name end
            for _,platform in pairs(p.force.platforms) do
                if not platform.hidden then
                    local hub=platform.hub
                    local starter=platform.starter_pack
                    entries[#entries+1]={name=platform.name,index=platform.index,state=states[platform.state],
                        surface=platform.surface and platform.surface.name or nil,hub=hub and hub.position or nil,
                        space_location=platform.space_location and platform.space_location.name or nil,
                        space_connection=platform.space_connection and platform.space_connection.name or nil,
                        last_visited=platform.last_visited_space_location and platform.last_visited_space_location.name or nil,
                        distance=platform.distance,speed=platform.speed,weight=platform.weight,paused=platform.paused,schedule=platform.schedule,
                        scheduled_for_deletion=platform.scheduled_for_deletion,starter_pack=starter and {name=starter.name.name,quality=starter.quality.name} or nil}
                end
            end
        """
    }.trimIndent()
    val result = executeOnTick(
        """
        local p=assert(__factorio_mcp_resident_v1).player()
        local entries={}
        $entries
        local filtered={}
        for _,entry in ipairs(entries) do
            if ${name?.let { "entry.name==${luaQuote(it)}" } ?: "true"} then filtered[#filtered+1]=entry end
        end
        table.sort(filtered,function(a,b) return a.name==b.name and (a.index or 0)<(b.index or 0) or a.name<b.name end)
        local items={}
        for i=${page.offset}+1,math.min(#filtered,${page.end}) do items[#items+1]=filtered[i] end
        return {tick=game.tick,section=${luaQuote(section)},force=p.force.name,platforms_unlocked=p.force.is_space_platforms_unlocked(),
            total=#filtered,offset=${page.offset},items=items}
    """.trimIndent(), timeoutMillis).jsonObject.withLuaArrays("items")
    if (section != "platforms") return result.toString()
    val items = result.getValue("items").luaArray().map { entry ->
        val platform = entry.jsonObject
        val schedule = platform["schedule"]?.jsonObject ?: return@map platform
        val records = schedule["records"]?.luaArray().orEmpty().map { it.jsonObject.withLuaArrays("wait_conditions") }
        JsonObject(platform + ("schedule" to JsonObject(schedule + ("records" to JsonArray(records)))))
    }
    return JsonObject(result + ("items" to JsonArray(items))).toString()
}
