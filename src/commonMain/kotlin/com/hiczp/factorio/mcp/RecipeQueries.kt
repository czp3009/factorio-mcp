package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*

/** Prototype content and force availability are deliberately separate live queries. */
internal suspend fun GameProcess.recipes(args: JsonObject, available: Boolean, timeoutMillis: Int): String {
    val page = QueryPage(args)
    val name = args["name"]?.jsonPrimitive?.content
    val search = args["search"]?.jsonPrimitive?.content ?: ""
    val category = args["category"]?.jsonPrimitive?.content
    val ingredient = args["ingredient"]?.jsonPrimitive?.content
    val product = args["product"]?.jsonPrimitive?.content
    val includeHidden = args["include_hidden"]?.jsonPrimitive?.boolean ?: false
    val snapshot = executeOnTick(
        """
        local p=assert(__factorio_mcp_resident_v1).player()
        local names,items={},{}
        local search=string.lower(${luaQuote(search)})
        local function contains(entries,name)
            for _,entry in pairs(entries) do if entry.name==name then return true end end
            return false
        end
        ${
        if (name == null) "local candidates=prototypes.recipe" else "local candidates={[${luaQuote(name)}]=assert(prototypes.recipe[${
            luaQuote(
                name
            )
        }], 'recipe not found')}"
    }
        for n,r in pairs(candidates) do
            if (${name != null || includeHidden} or not r.hidden)
                and (search=='' or string.find(string.lower(n),search,1,true))
                ${category?.let { "and r.category==${luaQuote(it)}" } ?: ""}
                ${ingredient?.let { "and contains(r.ingredients,${luaQuote(it)})" } ?: ""}
                ${product?.let { "and contains(r.products,${luaQuote(it)})" } ?: ""}
                ${if (available) "and p.force.recipes[n] and p.force.recipes[n].enabled" else ""} then
                names[#names+1]=n
            end
        end
        table.sort(names)
        for i=${page.offset}+1,math.min(#names,${page.end}) do
            local n=names[i]
            local r=prototypes.recipe[n]
            ${
        if (available) """
            items[#items+1]={name=n,enabled=true,category=r.category,
                hidden=r.hidden,hidden_from_player_crafting=r.hidden_from_player_crafting}
            """.trimIndent() else """
            items[#items+1]={name=n,category=r.category,energy=r.energy,ingredients=r.ingredients,
                products=r.products,hidden=r.hidden,hidden_from_player_crafting=r.hidden_from_player_crafting,
                allow_as_intermediate=r.allow_as_intermediate,allow_decomposition=r.allow_decomposition,surface_conditions=r.surface_conditions}
            """.trimIndent()
    }
        end
        return {tick=game.tick,force=${if (available) "p.force.name" else "nil"},total=#names,offset=${page.offset},items=items}
    """.trimIndent(), timeoutMillis).jsonObject
    return JsonObject(snapshot + ("items" to JsonArray(snapshot.getValue("items").luaArray().map {
        if (available) it else it.jsonObject.withLuaArrays("ingredients", "products", "surface_conditions")
    }))).toString()
}

internal suspend fun GameProcess.craftingCheck(args: JsonObject, timeoutMillis: Int): String {
    val name = args.getValue("recipe").jsonPrimitive.content
    val count = args["count"]?.jsonPrimitive?.int ?: 1
    require(name.isNotBlank() && count in 1..100) { "A recipe and count in 1..100 are required" }
    val snapshot = executeOnTick(
        """
        local p=assert(__factorio_mcp_resident_v1).player()
        local recipe=assert(p.force.recipes[${luaQuote(name)}], 'recipe not found')
        local r=recipe.prototype
        local materials,unlocks={},{}
        local inventory=p.get_main_inventory()
        for _,ingredient in pairs(r.ingredients) do
            materials[#materials+1]={ingredient=ingredient,available=ingredient.type=='item' and inventory and
                inventory.get_item_count{name=ingredient.name,quality='normal'} or 0}
        end
        for n,technology in pairs(p.force.technologies) do
            for _,effect in pairs(technology.prototype.effects) do
                if effect.type=='unlock-recipe' and effect.recipe==recipe.name then
                    local prerequisites={}
                    for prerequisite,t in pairs(technology.prerequisites) do
                        if not t.researched then prerequisites[#prerequisites+1]=prerequisite end
                    end
                    table.sort(prerequisites)
                    unlocks[#unlocks+1]={name=n,researched=technology.researched,enabled=technology.enabled,
                        missing_prerequisites=prerequisites}
                end
            end
        end
        table.sort(unlocks,function(a,b) return a.name<b.name end)
        return {tick=game.tick,force=p.force.name,recipe=recipe.name,enabled=recipe.enabled,
            has_character=p.character~=nil,craftable_count=p.character and p.get_craftable_count(recipe.name) or 0,
            hand_crafting_disabled=p.force.get_hand_crafting_disabled_for_recipe(recipe.name),
            hidden_from_player_crafting=r.hidden_from_player_crafting,materials=materials,unlock_technologies=unlocks}
    """.trimIndent(), timeoutMillis
    ).jsonObject
    return buildJsonObject {
        for ((key, value) in snapshot) if (key != "materials") put(key, value)
        put("unlock_technologies", JsonArray(snapshot.getValue("unlock_technologies").luaArray().map {
            it.jsonObject.withLuaArrays("missing_prerequisites")
        }))
        put("requested_crafts", count)
        put(
            "can_queue", snapshot.getValue("enabled").jsonPrimitive.boolean &&
                    !snapshot.getValue("hand_crafting_disabled").jsonPrimitive.boolean &&
                    !snapshot.getValue("hidden_from_player_crafting").jsonPrimitive.boolean &&
                    snapshot.getValue("craftable_count").jsonPrimitive.int >= count
        )
        putJsonArray("direct_ingredients") {
            for (entry in snapshot.getValue("materials").luaArray()) {
                val material = entry.jsonObject
                val ingredient = material.getValue("ingredient").jsonObject
                val required = ingredient.getValue("amount").jsonPrimitive.double * count
                val available = material.getValue("available").jsonPrimitive.double
                add(buildJsonObject {
                    for ((key, value) in ingredient) put(key, value)
                    put("required", required)
                    put("available_in_main_inventory", available)
                    put("shortfall", maxOf(0.0, required - available))
                })
            }
        }
        put(
            "material_scope",
            "Direct normal-quality ingredients only; craftable_count is the game's hand-crafting assessment, including eligible intermediates. Fluid requirements are not character inventory items."
        )
    }.toString()
}
