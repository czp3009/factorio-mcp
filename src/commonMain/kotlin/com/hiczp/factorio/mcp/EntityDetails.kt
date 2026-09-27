package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*

internal val entityIncludes = setOf("recipe", "fluids", "filters")

internal fun validateEntityIncludes(value: JsonElement): List<String> {
    val includes = value.jsonArray.map { it.stringArgument() }
    require(
        includes.size in 1..entityIncludes.size &&
                includes.distinct().size == includes.size &&
                includes.all { it in entityIncludes }
    ) {
        "include must select distinct entries from ${entityIncludes.joinToString()}"
    }
    return includes
}

internal fun entityIncludesSchema() = buildJsonObject {
    put("type", "array")
    put("minItems", 1)
    put("maxItems", entityIncludes.size)
    put("uniqueItems", true)
    putJsonObject("items") {
        put("type", "string")
        putJsonArray("enum") { entityIncludes.forEach { add(it) } }
    }
    put(
        "description",
        "Entity details: recipe returns the configured recipe and quality; fluids returns native fluid amounts; filters returns bounded indexed filters and native mode flags. Availability/errors are explicit. These are observations, not production or action eligibility.",
    )
}

/** Query methods are explicit; no arbitrary method names or Lua supplied by callers. */
internal val entityDetailsLua =
    """
    local function entity_details(entity,record)
      if not args.include then return end
      record.details={}
      record.detail_status={}
      for _,name in ipairs(args.include) do
        local ok,value=pcall(function()
          if name=="recipe" then
            local recipe,quality=entity.get_recipe()
            if not recipe then return nil end
            return {recipe=identity(recipe),quality=quality and identity(quality) or nil,
              quality_availability=quality and "present" or "nil"}
          elseif name=="fluids" then
            return project(entity.get_fluid_contents(),0)
          elseif name=="filters" then
            local count=entity.filter_slot_count
            if count==nil then return nil end
            local maximum=math.min(count,128)
            local result={slots={},slot_count=count,truncated=count>maximum,attributes={},read_status={}}
            for _,field in ipairs{"use_filters","inserter_filter_mode","loader_filter_mode","mining_drill_filter_mode"} do
              local valid,mode=pcall(function() return entity[field] end)
              if not valid then result.read_status[field]={status="error",message=tostring(mode)}
              elseif mode==nil then result.read_status[field]={status="nil"}
              else result.attributes[field]=project(mode,0) end
            end
            for index=1,maximum do
              local slot={index=index}
              local valid,filter=pcall(function() return entity.get_filter(index) end)
              if not valid then slot.read_status={status="error",message=tostring(filter)}
              elseif filter==nil then slot.read_status={status="nil"}
              else slot.filter=project(filter,0) end
              result.slots[#result.slots+1]=slot
            end
            return result
          end
          error("Unsupported entity detail")
        end)
        if not ok then record.detail_status[name]={status="error",message=tostring(value)}
        elseif value==nil then record.detail_status[name]={status="nil"}
        else record.details[name]=value end
      end
    end
    """
        .trimIndent()
