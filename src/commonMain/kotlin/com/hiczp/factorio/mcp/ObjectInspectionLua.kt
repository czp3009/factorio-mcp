package com.hiczp.factorio.mcp

/** Metadata-gated native reads and stored plain values; caller code is never evaluated. */
internal val objectInspectionLua = """
    $utf8SliceLua
    $valueObservationLua
    local function public_path(path)
      local result={}
      for _,step in ipairs(path or {}) do
        result[#result+1]={property=step.property,index=step.index,method=step.method,
          arguments=step.arguments,result=step.result}
      end
      return result
    end
    local function follow(object,path,hint)
      for index,step in ipairs(path) do
        assert(object~=nil,"Inspection path reached nil before step "..index)
        if step.property then object,hint=read_property(object,step.property,hint)
        elseif step.index~=nil then object,hint=read_index(object,step.index,hint)
        else
          local class,metadata=api_class(object,hint)
          assert(metadata and admitted(metadata.methods,step.method),"Method is not an admitted query on this object")
          local values=table.pack(object[step.method](table.unpack(step.arguments or {},1,step.argument_count)))
          local position=step.result or 1
          object=values[position]
          hint=metadata.method_types and metadata.method_types[step.method] and metadata.method_types[step.method][position]
        end
      end
      return object,hint
    end
    local inspect_object,read_observations
    inspect_object=function(object,hint,request,reads)
      if object==nil then return {availability="nil",truncated=false} end
      local class,metadata,declared=api_class(object,hint)
      local mode=request.mode
      if mode=="slice" then
        assert(type(object)=="string","slice requires a string path target")
        return slice_utf8(object,request.offset,request.limit)
      end
      local result={object_name=class,value_type=type(object),truncated=false}
      if declared and declared~=class then result.api_class=declared end
      if class then result.reference=native_reference(object,hint) end
      local state={}
      local function preview(value,value_hint)
        return observe_value(value,request.depth,native_reference,state,value_hint)
      end
      local function nested(target,value,value_hint)
        if not reads then return end
        target.reads=read_observations(value,value_hint,reads)
        for _,item in ipairs(target.reads) do
          if item.truncated then result.truncated=true end
          if item.depth_truncated then result.depth_truncated=true end
        end
      end
      if mode=="values" then
        if metadata or request.fields then
          result.attributes={}
          result.read_status={}
          for _,key in ipairs(request.fields or metadata.attributes) do
            local ok,value,value_hint=pcall(read_property,object,key,hint)
            if not ok then result.read_status[key]={status="error",message=tostring(value)}
            elseif value==nil then result.read_status[key]={status="nil"}
            else result.attributes[key]=preview(value,value_hint) end
          end
        else result.value=preview(object,hint) end
        nested(result,object,hint)
      elseif mode=="members" then
        assert(metadata,"Target has no runtime API member catalog")
        local keys={}
        local function add(name,kind)
          if not request.search or string.find(string.lower(name),string.lower(request.search),1,true) then
            keys[#keys+1]={name=name,kind=kind}
          end
        end
        for _,name in ipairs(metadata.attributes) do add(name,"attribute") end
        for _,name in ipairs(metadata.methods) do add(name,"method") end
        table.sort(keys,function(a,b) if a.name==b.name then return a.kind<b.kind end return a.name<b.name end)
        local total=#keys
        local finish=math.min(total,request.offset+(request.limit or total))
        result.members={}
        for index=request.offset+1,finish do result.members[#result.members+1]=keys[index] end
        result.indexed=metadata.indexed
        result.has_length=metadata.length
        result.iterable=metadata.iterator
        result.offset=request.offset
        result.total=total
        result.has_more=finish<total
        result.next_offset=result.has_more and finish or nil
        result.search=request.search
        result.truncated=result.has_more
      else
        result.entries={}
        result.offset=request.offset
        local function entry(key,value,value_hint,iterator)
          local item=iterator and {index=key} or {key=key}
          if value==nil then item.read_status={status="nil"}
          else
            nested(item,value,value_hint)
            item.value=preview(value,value_hint)
          end
          result.entries[#result.entries+1]=item
        end
        if metadata and metadata.iterator then
          local scanned=0
          result.value_type="iterator"
          while true do
            local value=object()
            if value==nil then result.total=scanned result.has_more=false break end
            scanned=scanned+1
            if scanned>request.offset+request.limit then result.has_more=true break end
            if scanned>request.offset then entry(scanned,value,nil,true) end
          end
          result.next_offset=result.has_more and request.offset+#result.entries or nil
        else
          local keys,total=collection_keys(object,hint)
          local finish=math.min(total,request.offset+request.limit)
          for index=request.offset+1,finish do
            local key=keys and keys[index] or index
            local ok,value,value_hint=pcall(read_index,object,key,hint)
            if ok then entry(key,value,value_hint,false)
            else result.entries[#result.entries+1]={key=key,read_status={status="error",message=tostring(value)}} end
          end
          result.total=total
          result.has_more=finish<total
          result.next_offset=result.has_more and finish or nil
        end
        result.truncated=result.truncated or result.has_more
      end
      if state.depth_truncated then result.depth_truncated=true end
      if result.depth_truncated then result.truncated=true end
      return result
    end
    read_observations=function(object,hint,requests)
      local result={}
      for _,request in ipairs(requests) do
        local ok,value=pcall(function()
          local selected,selected_hint=follow(object,request.path,hint)
          return inspect_object(selected,selected_hint,request)
        end)
        local item=ok and value or {read_status={status="error",message=tostring(value)},truncated=false}
        item.path=public_path(request.path)
        item.mode=request.mode
        result[#result+1]=item
      end
      return result
    end
""".trimIndent()

internal val objectInspectionBranchLua = """
    local root,hint
    if selection.kind=="entities" then
      local found=entities(selection,2)
      assert(#found==1,"Inspection target must resolve exactly one entity")
      root=found[1]
    elseif selection.kind=="game" then root=game
    elseif selection.kind=="prototypes" then root=prototypes
    elseif selection.kind=="defines" then root=defines
    elseif selection.kind=="player" then root=selected_player
    elseif selection.kind=="force" then root=selected_force
    elseif selection.kind=="surface" then root=selection.name and game.get_surface(selection.name) or surface
    elseif selection.kind=="planet" then root=game.planets[selection.name]
    elseif selection.kind=="prototype" then
      local catalog,catalog_hint=read_property(prototypes,selection.type)
      root,hint=read_index(catalog,selection.name,catalog_hint)
    elseif selection.kind=="recipe" then root=selected_force.recipes[selection.name]
    elseif selection.kind=="technology" then root=selected_force.technologies[selection.name]
    else root=selected_player[selection.kind] end
    assert(root~=nil,"Inspection root is unavailable")
    local selected,selected_hint=follow(root,args.inspection.path,hint)
    objects[1]=inspect_object(selected,selected_hint,args.inspection,args.reads)
    candidates=1
    extra.observation="object_inspection"
    extra.path=public_path(args.inspection.path)
    truncated=objects[1].truncated
    extra.depth_truncated=objects[1].depth_truncated
    extra.has_more=objects[1].has_more
    extra.next_offset=objects[1].next_offset
""".trimIndent()
