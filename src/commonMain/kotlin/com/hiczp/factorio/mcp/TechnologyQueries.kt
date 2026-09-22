package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*

internal suspend fun GameProcess.technologies(args: JsonObject, timeoutMillis: Int): String {
    val page = QueryPage(args)
    val name = args["name"]?.jsonPrimitive?.content
    val search = args["search"]?.jsonPrimitive?.content?.lowercase() ?: ""
    val available = args["available"]?.jsonPrimitive?.boolean ?: false
    val result = executeOnTick(
        """
        local p=assert(__factorio_mcp_resident_v1).player()
        local names,items={},{}
        ${name?.let { "assert(p.force.technologies[${luaQuote(it)}], 'technology not found')" } ?: ""}
        for n,t in pairs(p.force.technologies) do
            local ready=t.enabled and not t.researched
            for _,prerequisite in pairs(t.prerequisites) do if not prerequisite.researched then ready=false end end
            if ${name?.let { "n==${luaQuote(it)}" } ?: "true"} and n:lower():find(${luaQuote(search)},1,true)
                and (not $available or ready) then names[#names+1]=n end
        end
        table.sort(names)
        for i=${page.offset}+1,math.min(#names,${page.end}) do
            local t=p.force.technologies[names[i]]
            local prerequisites,missing={},{}
            for n,prerequisite in pairs(t.prerequisites) do
                prerequisites[#prerequisites+1]=n
                if not prerequisite.researched then missing[#missing+1]=n end
            end
            table.sort(prerequisites);table.sort(missing)
            items[#items+1]={name=t.name,researched=t.researched,enabled=t.enabled,level=t.level,
                available=t.enabled and not t.researched and #missing==0,prerequisites=prerequisites,
                missing_prerequisites=missing,ingredients=t.research_unit_ingredients,
                unit_count=t.research_unit_count,unit_energy=t.research_unit_energy,
                count_formula=t.research_unit_count_formula,effects=t.prototype.effects,
                research_trigger=t.prototype.research_trigger}
        end
        return {tick=game.tick,force=p.force.name,total=#names,offset=${page.offset},items=items}
    """.trimIndent(), timeoutMillis).jsonObject
    return JsonObject(result + ("items" to JsonArray(result.getValue("items").luaArray().map {
        it.jsonObject.withLuaArrays("prerequisites", "missing_prerequisites", "ingredients", "effects")
    }))).toString()
}
