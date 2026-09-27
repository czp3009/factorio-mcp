package com.hiczp.factorio.mcp

/**
 * Metadata-gated property traversal and explicitly admitted passive queries. Never evaluates caller
 * code.
 */
internal val objectInspectionLua =
    """
    local function api_class(object)
      if type(object)~="userdata" and type(object)~="table" then return nil,nil end
      local ok,name=pcall(function() return object.object_name end)
      if not ok or type(name)~="string" then return nil,nil end
      return name,args.api and args.api[name]
    end
    local function admitted(values,name)
      for _,value in ipairs(values or {}) do if value==name then return true end end
      return false
    end
    local function read_property(object,name)
      local class,metadata=api_class(object)
      if class then
        assert(metadata and admitted(metadata.attributes,name),"Attribute is not in the target runtime API: "..name)
      else
        assert(type(object)=="table","Property target is not an object")
      end
      return object[name]
    end
    local function follow(object,path)
      for index,step in ipairs(path) do
        assert(object~=nil,"Inspection path reached nil before step "..index)
        if step.property then object=read_property(object,step.property)
        elseif step.index then
          local class,metadata=api_class(object)
          assert((class and metadata and metadata.indexed) or (not class and type(object)=="table"),"Object has no readable index operator")
          object=object[step.index]
        else
          local class,metadata=api_class(object)
          assert(metadata and admitted(metadata.methods,step.method),"Method is not an admitted query on this object")
          local values=table.pack(object[step.method](table.unpack(step.arguments or {},1,step.argument_count)))
          object=values[step.result or 1]
        end
      end
      return object
    end
    local budget=4096
    local function preview(value,depth)
      budget=budget-1
      if budget<0 then return {observation="omitted",reason="value_budget"} end
      local kind=type(value)
      if kind=="string" then
        if #value>4096 then return {observation="omitted",reason="string_bound",bytes=#value} end
        return value
      elseif kind=="nil" or kind=="boolean" then return value
      elseif kind=="number" then
        if value~=value or value==math.huge or value==-math.huge then return {observation="nonfinite",value=tostring(value)} end
        return value
      end
      local class,metadata=api_class(value)
      if class then
        local reference={object_name=class,observation="reference",members_available=metadata~=nil}
        for _,field in ipairs{"name","index","unit_number","valid"} do
          if metadata and admitted(metadata.attributes,field) then
            local ok,item=pcall(function() return value[field] end)
            if ok and (type(item)=="string" or type(item)=="number" or type(item)=="boolean") then reference[field]=preview(item,depth+1) end
          end
        end
        return reference
      end
      if kind~="table" then return {observation="unsupported",value_type=kind} end
      if depth>=3 then return {observation="reference",value_type="table",reason="depth_bound"} end
      local result,count={},0
      for key,item in pairs(value) do
        count=count+1
        if count>64 or budget<=0 then return {observation="partial",value=result,reason="collection_bound"} end
        if type(key)=="string" or type(key)=="number" then result[key]=preview(item,depth+1) end
      end
      return result
    end
    local function inspect_object(object)
      if object==nil then return {availability="nil"} end
      local class,metadata=api_class(object)
      local mode=args.inspection.mode
      local result={object_name=class,value_type=type(object),attributes={},read_status={},offset=args.offset,truncated=false}
      local keys={}
      if mode=="members" then
        assert(metadata,"Target has no runtime API member catalog; use entries for tables")
        for _,name in ipairs(metadata.attributes) do keys[#keys+1]={name=name,kind="attribute"} end
        for _,name in ipairs(metadata.methods) do keys[#keys+1]={name=name,kind="method"} end
        table.sort(keys,function(a,b) if a.name==b.name then return a.kind<b.kind end return a.name<b.name end)
        result.members={}
        result.indexed=metadata.indexed
        result.has_length=metadata.length
        for index=args.offset+1,math.min(#keys,args.offset+limit) do result.members[#result.members+1]=keys[index] end
      elseif metadata and mode=="values" then
        keys=#args.fields>0 and args.fields or metadata.attributes
        for _,key in ipairs(keys) do assert(admitted(metadata.attributes,key),"Unknown readable attribute: "..key) end
        for index=args.offset+1,math.min(#keys,args.offset+limit) do
          local key=keys[index]
          local ok,value=pcall(function() return preview(object[key],0) end)
          if not ok then result.read_status[key]={status="error",message=tostring(value)}
          elseif value==nil then result.read_status[key]={status="nil"}
          else result.attributes[key]=value end
        end
      elseif (not class and type(object)=="table") or (metadata and metadata.indexed) then
        assert(#args.fields==0,"Collection reads do not accept fields")
        local indexed=metadata and metadata.length and class~="LuaCustomTable"
        local total
        if indexed then
          total=#object
          assert(total>=0 and total<=65536,"Collection length exceeds inspection bound")
        else
          for key,_ in pairs(object) do
            assert(type(key)=="string" or type(key)=="number","Unsupported collection key")
            assert(#keys<65536,"Collection scan exceeds inspection bound; select an index directly")
            keys[#keys+1]=key
          end
          table.sort(keys,function(a,b) if type(a)==type(b) then return a<b end return type(a)<type(b) end)
          total=#keys
        end
        result.entries={}
        for index=args.offset+1,math.min(total,args.offset+limit) do
          local key=indexed and index or keys[index]
          local entry={key=key}
          local ok,value=pcall(function() return preview(object[key],0) end)
          if not ok then entry.read_status={status="error",message=tostring(value)}
          elseif value==nil then entry.read_status={status="nil"}
          else entry.value=value end
          result.entries[#result.entries+1]=entry
        end
        result.total=total
      else
        assert(mode=="values","Target does not support collection/member inspection")
        if args.offset==0 then result.value=preview(object,0) end
        result.total=1
      end
      result.total=result.total or #keys
      result.truncated=args.offset+limit<result.total
      if result.truncated then result.next_offset=args.offset+limit end
      result.preview_budget_exhausted=budget<=0
      return result
    end
    """
        .trimIndent()

internal val objectInspectionBranchLua =
    """
    local root
    if selection.kind=="entities" then
      local found=entities(selection,2)
      assert(#found==1,"Inspection target must resolve exactly one entity")
      root=found[1]
    elseif selection.kind=="game" then root=game
    elseif selection.kind=="player" then root=selected_player
    elseif selection.kind=="force" then root=selected_player.force
    elseif selection.kind=="surface" then root=selection.name and game.get_surface(selection.name) or surface
    elseif selection.kind=="planet" then root=game.planets[selection.name]
    elseif selection.kind=="prototype" then root=prototypes[selection.type][selection.name]
    elseif selection.kind=="recipe" then root=selected_player.force.recipes[selection.name]
    elseif selection.kind=="technology" then root=selected_player.force.technologies[selection.name]
    else root=selected_player[selection.kind] end
    assert(root~=nil,"Inspection root is unavailable")
    objects[1]=inspect_object(follow(root,args.inspection.path))
    candidates=1
    extra.observation="object_inspection"
    extra.path={}
    for _,step in ipairs(args.inspection.path) do
      extra.path[#extra.path+1]={property=step.property,index=step.index,method=step.method,arguments=step.arguments,result=step.result}
    end
    truncated=objects[1].truncated or objects[1].preview_budget_exhausted or false
    """
        .trimIndent()
