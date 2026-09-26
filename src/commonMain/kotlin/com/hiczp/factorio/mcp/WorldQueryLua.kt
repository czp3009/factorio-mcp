package com.hiczp.factorio.mcp

/** Fixed read-only program; tool arguments are passed as data, never interpolated into Lua. */
internal val worldQueryLua =
    """
    local player_index, arguments_json, view_surface, view_width, view_height,
      view_left, view_top, view_right, view_bottom, view_error = ...
    local args = helpers.json_to_table(arguments_json)
    local player = game.get_player(player_index)
    assert(player and player.valid, "Local player is unavailable")
    local viewport
    if args.selection.kind=="overview" then
      viewport={available=view_error=="", reason=view_error~="" and view_error or nil}
      if viewport.available then
        viewport.surface_index=view_surface
        viewport.width=view_width
        viewport.height=view_height
        viewport.area={left_top={x=view_left,y=view_top},right_bottom={x=view_right,y=view_bottom}}
        viewport.occlusion="not_evaluated"
        viewport.coordinate_space="client_content_pixels"
      end
      if not args.selection.area then
        assert(viewport.available, "Viewport unavailable: " .. tostring(view_error))
        args.selection.area=viewport.area
        args.surface=view_surface
      end
    end
    local surface
    if args.surface then surface = game.get_surface(args.surface) else surface = player.surface end
    assert(surface and surface.valid, "Requested surface is unavailable")
    local selection, limit = args.selection, args.limit
    local function identity(value)
      local class = value.object_name
      if class == "LuaSurface" or class == "LuaForce" then
        return {object_name=class, index=value.index, name=value.name}
      elseif class == "LuaQualityPrototype" then
        return {object_name=class, name=value.name, level=value.level}
      elseif class == "LuaEntity" then
        return {object_name=class, name=value.name, type=value.type, unit_number=value.unit_number,
          position=value.position, surface={index=value.surface.index, name=value.surface.name}}
      elseif class == "LuaTrain" then
        return {object_name=class, id=value.id}
      elseif class == "LuaItemStack" then
        if not value.valid_for_read then return {object_name=class, valid_for_read=false} end
        return {object_name=class, valid_for_read=true, name=value.name, count=value.count,
          quality={name=value.quality.name, level=value.quality.level}}
      elseif class == "LuaGroup" then
        return {object_name=class, name=value.name}
      elseif class == "LuaPlayer" then
        return {object_name=class, index=value.index, name=value.name}
      elseif class == "LuaTechnology" then
        return {object_name=class, name=value.name, force={index=value.force.index, name=value.force.name}}
      elseif class == "LuaItemPrototype" or class == "LuaRecipePrototype" or class == "LuaEntityPrototype"
          or class == "LuaFluidPrototype" or class == "LuaTechnologyPrototype" then
        return {object_name=class, name=value.name, type=value.type}
      end
      error("Unsupported related object: " .. tostring(class))
    end
    local remaining = 32768
    local function project(value, depth)
      remaining = remaining - 1
      assert(remaining >= 0 and depth <= 8, "Projection work exceeds bound")
      local kind = type(value)
      if kind == "nil" or kind == "boolean" or kind == "number" or kind == "string" then return value end
      if kind == "userdata" then return identity(value) end
      assert(kind == "table", "Unsupported value type: " .. kind)
      local result, count = {}, 0
      for key, item in pairs(value) do
        count = count + 1
        assert(count <= 512, "Related collection exceeds bound")
        assert(type(key) == "string" or type(key) == "number", "Unsupported table key")
        result[key] = project(item, depth + 1)
      end
      return result
    end
    local visibility_cache = {}
    local force = player.force
    local function visibility(position, observed_surface)
      observed_surface=observed_surface or surface
      local chunk = {x=math.floor(position.x/32), y=math.floor(position.y/32)}
      local cache=visibility_cache[observed_surface.index]
      if not cache then cache={} visibility_cache[observed_surface.index]=cache end
      local column = cache[chunk.x]
      if not column then
        column = {}
        cache[chunk.x] = column
      end
      local result = column[chunk.y]
      if not result then
        result = {basis="position_chunk", surface_index=observed_surface.index, generated=observed_surface.is_chunk_generated(chunk),
          charted=force.is_chunk_charted(observed_surface, chunk), visible=force.is_chunk_visible(observed_surface, chunk)}
        column[chunk.y] = result
      end
      return result
    end
    local function describe(object)
      local result = {object_name=object.object_name, attributes={}, read_status={}}
      for _, field in ipairs(args.fields) do
        local ok, value = pcall(function() return project(object[field], 0) end)
        if not ok then
          result.read_status[field] = {status="error", message=tostring(value)}
        elseif value == nil then
          result.read_status[field] = {status="nil"}
        else
          result.attributes[field] = value
        end
      end
      local ok, position = pcall(function() return object.position end)
      if ok and position then result.visibility = visibility(position, object.surface) end
      return result
    end
    local objects, truncated, candidates = {}, false, 0
    local extra = {}
    local function entities(selected, maximum)
      if selected.unit_number and not selected.position and not selected.area then
        local entity = game.get_entity_by_unit_number(selected.unit_number)
        assert(entity, "Unit number is absent or not indexed by the game; provide position or area for bounded resolution")
        return entity and entity.surface == surface and {entity} or {}
      end
      local found=surface.find_entities_filtered{position=selected.position, area=selected.area,
        radius=selected.radius, name=selected.name, type=selected.type, limit=selected.unit_number and 4097 or maximum}
      if not selected.unit_number then return found end
      assert(#found<=4096, "Unit-number spatial search exceeds 4096 candidates; narrow the area")
      for _,entity in ipairs(found) do
        if entity.unit_number==selected.unit_number then return {entity} end
      end
      return {}
    end
    local function inventory_info(inventory)
      local result = {object_name="LuaInventory", name=inventory.name, index=inventory.index, size=#inventory,
        supports_filters=inventory.supports_filters(), supports_bar=inventory.supports_bar()}
      if result.supports_bar then result.bar=inventory.get_bar() end
      return result
    end
    if selection.kind == "overview" then
      $worldOverviewLua
    elseif selection.kind == "prototypes" or selection.kind == "recipes" or selection.kind == "technologies" then
      local catalog
      if selection.kind=="recipes" then catalog=player.force.recipes
      elseif selection.kind=="technologies" then catalog=player.force.technologies
      else catalog=prototypes[selection.type] end
      assert(catalog, "Requested prototype catalog is unavailable")
      local names, missing, scanned = {}, {}, 0
      local relations=0
      local function contains(entries, filter)
        if not filter then return true end
        for _,entry in ipairs(entries) do
          relations=relations+1
          assert(relations<=32768, "Recipe relation scan exceeds 32768 entries; narrow names or search")
          if entry.type==filter.type and entry.name==filter.name then return true end
        end
        return false
      end
      local function consider(name, value)
        scanned=scanned+1
        assert(scanned<=65536, "Catalog discovery exceeds 65536 entries; select exact names")
        if not value then missing[#missing+1]=name
        elseif not selection.search or string.find(string.lower(name), string.lower(selection.search), 1, true) then
          if (not selection.product or contains(value.products, selection.product))
              and (not selection.ingredient or contains(value.ingredients, selection.ingredient)) then
            names[#names+1]=name
          end
        end
      end
      if selection.names then
        for _, name in ipairs(selection.names) do consider(name, catalog[name]) end
      else
        for name, value in pairs(catalog) do consider(name, value) end
      end
      table.sort(names)
      table.sort(missing)
      extra.missing_names=missing
      extra.observation=selection.kind == "prototypes" and "prototype_catalog" or "force_"..selection.kind
      extra.offset=args.offset
      candidates=#names
      truncated=args.offset+limit<#names
      if truncated then extra.next_offset=args.offset+limit end
      for index=args.offset+1,math.min(#names,args.offset+limit) do
        objects[#objects+1]=describe(catalog[names[index]])
      end
    elseif selection.kind == "inventory" or selection.kind == "inventories" then
      local owner=player
      if selection.owner and selection.owner.kind == "entities" then
        local found=entities(selection.owner,2)
        assert(#found==1, #found==0 and "Inventory owner matched no entity" or "Inventory owner is ambiguous")
        owner=found[1]
      elseif selection.owner and selection.owner.kind~="player" then
        owner=player[selection.owner.kind]
        assert(owner and owner.valid, "Requested local inventory owner is unavailable")
      end
      extra.owner=identity(owner)
      extra.offset=args.offset
      extra.index_base=1
      if selection.kind == "inventories" then
        local indices, seen={},{}
        for _, index in pairs(defines.inventory) do
          if not seen[index] then
            assert(#indices<256, "Inventory discovery exceeds bound")
            indices[#indices+1]=index
            seen[index]=true
          end
        end
        table.sort(indices)
        local found={}
        for _, index in ipairs(indices) do
          local inventory=owner.get_inventory(index)
          if inventory then found[#found+1]=inventory_info(inventory) end
        end
        candidates=#found
        truncated=args.offset+limit<#found
        if truncated then extra.next_offset=args.offset+limit end
        for index=args.offset+1,math.min(#found,args.offset+limit) do objects[#objects+1]=found[index] end
      else
        local requested=selection.inventory or "main"
        local inventory
        if requested=="main" then inventory=owner.get_main_inventory()
        else
          assert(defines.inventory[requested], "Unknown inventory name")
          inventory=owner.get_inventory(defines.inventory[requested])
          assert(not inventory or inventory.name==requested, "Inventory name does not belong to this owner; use inventories discovery")
        end
        assert(inventory and inventory.valid, "Requested inventory is unavailable")
        extra.inventory=inventory_info(inventory)
        candidates=#inventory
        truncated=args.offset+limit<#inventory
        if truncated then extra.next_offset=args.offset+limit end
        for index=args.offset+1,math.min(#inventory,args.offset+limit) do
          local stack=inventory[index]
          local record=stack.valid_for_read and describe(stack) or {object_name="LuaItemStack",attributes={},read_status={}}
          record.index=index
          record.valid_for_read=stack.valid_for_read
          if extra.inventory.supports_filters then
            local filter=inventory.get_filter(index)
            if filter then record.filter=project(filter,0) end
          end
          objects[#objects+1]=record
        end
      end
    elseif selection.kind == "quickbar" then
      local function indexed(index, field, getter)
        local record={index=index, attributes={}, read_status={}}
        local ok,value=pcall(function() return project(getter(index),0) end)
        if not ok then record.read_status[field]={status="error",message=tostring(value)}
        elseif value==nil then record.read_status[field]={status="nil"}
        else record.attributes[field]=value end
        return record
      end
      for _,index in ipairs(selection.slots or {}) do
        objects[#objects+1]=indexed(index,"filter",player.get_quick_bar_slot)
      end
      extra.active_pages={}
      for _,index in ipairs(selection.screen_pages or {}) do
        extra.active_pages[#extra.active_pages+1]=indexed(index,"page",player.get_active_quick_bar_page)
      end
      extra.index_base=1
      extra.observation="quickbar_filters"
      candidates=#objects
    elseif selection.kind == "player" then
      objects[1] = describe(player)
      candidates = 1
    elseif selection.kind == "force" then
      objects[1] = describe(player.force)
      candidates = 1
    elseif selection.kind == "character" or selection.kind == "vehicle" or selection.kind == "physical_vehicle" then
      local entity=player[selection.kind]
      if entity then
        objects[1]=describe(entity)
        candidates=1
        extra.surface=identity(entity.surface)
      else extra.availability="nil" end
    elseif selection.kind == "entities" then
      local found=entities(selection,limit+1)
      candidates = #found
      truncated = #found > limit
      for index=1,math.min(#found,limit) do objects[index] = describe(found[index]) end
    else
      local left, top, right, bottom
      if selection.position then
        left, top = math.floor(selection.position.x), math.floor(selection.position.y)
        right, bottom = left+1, top+1
      else
        left, top = math.floor(selection.area.left_top.x), math.floor(selection.area.left_top.y)
        right, bottom = math.ceil(selection.area.right_bottom.x), math.ceil(selection.area.right_bottom.y)
      end
      candidates = (right-left)*(bottom-top)
      truncated = candidates > limit
      for index=0,math.min(candidates,limit)-1 do
        local x, y = left+index%(right-left), top+math.floor(index/(right-left))
        local visible = visibility{x=x,y=y}
        if visible.generated then objects[#objects+1] = describe(surface.get_tile(x,y))
        else objects[#objects+1] = {object_name="LuaTile", position={x=x,y=y}, availability="ungenerated", visibility=visible} end
      end
    end
    local result={tick=game.tick, observation="live_world", identity_scope="current_world",
      player={index=player.index, name=player.name, controller_type=player.controller_type,
        position=player.position, surface=identity(player.surface), physical_position=player.physical_position,
        physical_surface=identity(player.physical_surface), zoom=player.zoom},
      surface=identity(surface), force=identity(player.force), selection=selection, limit=limit, truncated=truncated,
      candidates_observed=candidates, objects=objects}
    for key,value in pairs(extra) do result[key]=value end
    return helpers.table_to_json(result)
    """
        .trimIndent()
