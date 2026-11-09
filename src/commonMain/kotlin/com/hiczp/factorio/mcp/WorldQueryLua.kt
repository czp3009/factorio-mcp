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
    local selected_player=player
    if selection.player then
      selected_player=game.get_player(selection.player)
      assert(selected_player and selected_player.valid, "Selected player is unavailable")
    end
    local selected_force=selection.force and game.forces[selection.force] or selected_player.force
    assert(selected_force and selected_force.valid,"Selected force is unavailable")
    $objectInspectionLua
    local value_state={}
    local function identity(value,hint)
      local class = value.object_name
      if class == "LuaSurface" or class == "LuaForce" then
        local result={object_name=class, index=value.index, name=value.name}
        if class=="LuaSurface" then
          local planet=value.planet
          result.planet=planet and {object_name=planet.object_name,name=planet.name} or nil
          result.planet_availability=planet and "present" or "nil"
        end
        return result
      elseif class == "LuaQualityPrototype" then
        return {object_name=class, name=value.name, level=value.level}
      elseif class == "LuaEntity" then
        return {object_name=class, name=value.name, type=value.type, unit_number=value.unit_number,
          position=value.position, direction=value.direction, surface={index=value.surface.index, name=value.surface.name}}
      elseif class == "LuaTrain" then
        return {object_name=class, id=value.id}
      elseif class == "LuaRecord" then
        return {object_name=class, valid=value.valid, type=value.type}
      elseif class == "LuaItem" then
        return {object_name=class, valid=value.valid, name=value.name,
          quality={name=value.quality.name, level=value.quality.level}}
      elseif class == "LuaItemStack" then
        if not value.valid_for_read then return {object_name=class, valid_for_read=false} end
        return {object_name=class, valid_for_read=true, name=value.name, count=value.count,
          quality={name=value.quality.name, level=value.quality.level}}
      elseif class == "LuaItemGroup" or class == "LuaItemSubGroup" then
        return {object_name=class, name=value.name}
      elseif class == "LuaPlayer" then
        return {object_name=class, index=value.index, name=value.name}
      elseif class == "LuaTechnology" or class == "LuaRecipe" then
        return {object_name=class, name=value.name, force={index=value.force.index, name=value.force.name}}
      elseif class == "LuaItemPrototype" or class == "LuaRecipePrototype" or class == "LuaEntityPrototype"
          or class == "LuaFluidPrototype" or class == "LuaTechnologyPrototype" then
        return {object_name=class, name=value.name, type=value.type}
      end
      return native_reference(value,hint)
    end
    local function project(value,hint)
      return observe_value(value,args.depth or 3,identity,value_state,hint)
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
    $entityDetailsLua
    local reads_truncated=false
    local function collect_reads(object,result,hint)
      if not args.reads then return end
      result.reads=read_observations(object,hint,args.reads)
      for _,item in ipairs(result.reads) do
        if item.truncated then reads_truncated=true end
        if item.depth_truncated then value_state.depth_truncated=true end
      end
    end
    local function describe(object,hint)
      local class,metadata,declared=api_class(object,hint)
      local result = {object_name=class, attributes={}, read_status={}}
      if declared and declared~=class then result.api_class=declared end
      if class then result.reference=identity(object,hint)
      else result.value=project(object,hint) end
      for _, field in ipairs(not class and args.fields_explicit==false and {} or args.fields) do
        local ok, value = pcall(function()
          local item,item_hint=read_property(object,field,hint)
          return project(item,item_hint)
        end)
        if not ok then
          result.read_status[field] = {status="error", message=tostring(value)}
        elseif value == nil then
          result.read_status[field] = {status="nil"}
        else
          result.attributes[field] = value
        end
      end
      local ok, position, observed_surface = pcall(function()
        return read_property(object,"position",hint),read_property(object,"surface",hint)
      end)
      if ok and position and observed_surface then result.visibility = visibility(position, observed_surface) end
      entity_details(object,result)
      collect_reads(object,result,hint)
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
      local filters={}
      for key,value in pairs(selected.filters or {}) do filters[key]=value end
      filters.position=selected.position
      filters.area=selected.area
      filters.radius=selected.radius
      filters.name=selected.name or filters.name
      filters.type=selected.type or filters.type
      filters.limit=not selected.unit_number and maximum or nil
      local found=surface.find_entities_filtered(filters)
      if not selected.unit_number then return found end
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
    if args.inspection then
      $objectInspectionBranchLua
    elseif selection.kind == "overview" then
      $worldOverviewLua
    elseif selection.kind == "prototypes" or selection.kind == "recipes" or selection.kind == "technologies" then
      local catalog
      local catalog_hint
      if selection.kind=="recipes" then catalog=selected_force.recipes
      elseif selection.kind=="technologies" then catalog=selected_force.technologies
      else catalog,catalog_hint=read_property(prototypes,selection.type) end
      assert(catalog, "Requested prototype catalog is unavailable")
      local names, missing, scanned = {}, {}, 0
      local function contains(entries, filter)
        if not filter then return true end
        for _,entry in ipairs(entries) do
          if entry.type==filter.type and entry.name==filter.name then return true end
        end
        return false
      end
      local function consider(name, value)
        scanned=scanned+1
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
      local finish=math.min(#names,args.offset+(limit or #names))
      truncated=finish<#names
      if truncated then extra.next_offset=finish end
      for index=args.offset+1,finish do
        local value,hint=read_index(catalog,names[index],catalog_hint)
        local record=describe(value,hint)
        record.key=names[index]
        objects[#objects+1]=record
      end
    elseif selection.kind == "inventory" or selection.kind == "inventories" then
      local inventory_player=player
      if selection.owner and selection.owner.player then
        inventory_player=game.get_player(selection.owner.player)
        assert(inventory_player and inventory_player.valid, "Inventory player is unavailable")
      end
      local owner=inventory_player
      if selection.owner and selection.owner.kind == "entities" then
        local found=entities(selection.owner,2)
        assert(#found==1, #found==0 and "Inventory owner matched no entity" or "Inventory owner is ambiguous")
        owner=found[1]
      elseif selection.owner and selection.owner.kind~="player" then
        owner=inventory_player[selection.owner.kind]
        assert(owner and owner.valid, "Requested inventory owner is unavailable")
      end
      extra.owner=identity(owner)
      extra.offset=args.offset
      extra.index_base=1
      if selection.kind == "inventories" then
        local indices, seen={},{}
        for _, index in pairs(defines.inventory) do
          if not seen[index] then
            indices[#indices+1]=index
            seen[index]=true
          end
        end
        table.sort(indices)
        local found={}
        for _, index in ipairs(indices) do
          local inventory=owner.get_inventory(index)
          if inventory then found[#found+1]=inventory end
        end
        candidates=#found
        local finish=math.min(#found,args.offset+(limit or #found))
        truncated=finish<#found
        if truncated then extra.next_offset=finish end
        for index=args.offset+1,finish do
          local record=inventory_info(found[index])
          collect_reads(found[index],record)
          objects[#objects+1]=record
        end
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
        local finish=math.min(#inventory,args.offset+(limit or #inventory))
        truncated=finish<#inventory
        if truncated then extra.next_offset=finish end
        for index=args.offset+1,finish do
          local stack=inventory[index]
          local record=stack.valid_for_read and describe(stack) or {object_name="LuaItemStack",attributes={},read_status={}}
          if not stack.valid_for_read then collect_reads(stack,record) end
          record.index=index
          record.valid_for_read=stack.valid_for_read
          if extra.inventory.supports_filters then
            local filter=inventory.get_filter(index)
            if filter then record.filter=project(filter) end
          end
          objects[#objects+1]=record
        end
      end
    elseif selection.kind == "quickbar" then
      extra.page_width=player.quick_bar_width
      local function read(record, field, getter)
        record.attributes={}
        record.read_status={}
        local ok,value=pcall(function() return project(getter()) end)
        if not ok then record.read_status[field]={status="error",message=tostring(value)}
        elseif value==nil then record.read_status[field]={status="nil"}
        else record.attributes[field]=value end
        return record
      end
      for _,location in ipairs(selection.slots or {}) do
        objects[#objects+1]=read({page_index=location.page_index,slot_index=location.slot_index},"value",function()
          return player.get_quick_bar_slot(location.page_index,location.slot_index)
        end)
      end
      extra.active_pages={}
      for _,index in ipairs(selection.screen_pages or {}) do
        extra.active_pages[#extra.active_pages+1]=read({index=index},"page",function()
          return player.get_active_quick_bar_page(index)
        end)
      end
      extra.index_base=1
      extra.observation="quickbar_slots"
      candidates=#objects
    elseif selection.kind == "players" then
      local found={}
      local scanned=0
      local function contains(values,value)
        if not values then return true end
        for _,candidate in ipairs(values) do if candidate==value then return true end end
        return false
      end
      for _,candidate in pairs(game.players) do
        scanned=scanned+1
        if (selection.connected==nil or candidate.connected==selection.connected)
          and contains(selection.names,candidate.name) and contains(selection.indices,candidate.index) then
          found[#found+1]=candidate
        end
      end
      table.sort(found,function(a,b) return a.index<b.index end)
      candidates=#found
      extra.offset=args.offset
      local finish=math.min(#found,args.offset+(limit or #found))
      truncated=finish<#found
      if truncated then extra.next_offset=finish end
      for index=args.offset+1,finish do objects[#objects+1]=describe(found[index]) end
    elseif selection.kind == "player" then
      objects[1] = describe(selected_player)
      candidates = 1
    elseif selection.kind == "force" then
      objects[1] = describe(selected_force)
      candidates = 1
    elseif selection.kind == "character" or selection.kind == "vehicle" or selection.kind == "physical_vehicle" then
      local entity=selected_player[selection.kind]
      if entity then
        objects[1]=describe(entity)
        candidates=1
        extra.surface=identity(entity.surface)
      else extra.availability="nil" end
    elseif selection.kind == "entities" then
      local found=entities(selection,limit and limit+1 or 2)
      candidates = #found
      truncated = limit~=nil and #found>limit
      extra.selection_complete=not truncated
      for index=1,math.min(#found,limit or #found) do objects[index] = describe(found[index]) end
    else
      local left, top, right, bottom
      if selection.position then
        left, top = math.floor(selection.position.x), math.floor(selection.position.y)
        right, bottom = left+1, top+1
      else
        left, top = math.floor(selection.area.left_top.x), math.floor(selection.area.left_top.y)
        right, bottom = math.ceil(selection.area.right_bottom.x), math.ceil(selection.area.right_bottom.y)
      end
      local total=(right-left)*(bottom-top)
      local finish=math.min(total,limit or total)
      extra.total=total
      candidates=finish
      truncated = finish < total
      extra.selection_complete=not truncated
      for index=0,finish-1 do
        local x, y = left+index%(right-left), top+math.floor(index/(right-left))
        local visible = visibility{x=x,y=y}
        if visible.generated then objects[#objects+1] = describe(surface.get_tile(x,y))
        else objects[#objects+1] = {object_name="LuaTile", position={x=x,y=y}, availability="ungenerated", visibility=visible} end
      end
    end
    if not args.inspection and args.offset~=nil then extra.has_more=extra.next_offset~=nil end
    if value_state.depth_truncated then
      extra.depth_truncated=true
      truncated=true
    end
    truncated=truncated or reads_truncated
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
