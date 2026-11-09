package com.hiczp.factorio.mcp

/** Existing-object reads only. The binary argument contains indices, never borrowed game addresses. */
internal val guiObservationLua = """
    local player_index, arguments_json, payload = ...
    local args = helpers.json_to_table(arguments_json)
    local player = game.get_player(player_index)
    assert(player and player.valid, "Local player is unavailable")
    $utf8SliceLua
    local function u32(offset)
      assert(offset>=1 and offset+3<=#payload,"Invalid GUI index payload")
      local a,b,c,d=string.byte(payload,offset,offset+3)
      return a+b*256+c*65536+d*16777216
    end
    local phase,count=u32(1),u32(5)
    assert((phase==1 or phase==2) and #payload==8+count*8,"Invalid GUI index payload size")
    $valueObservationLua
    local by_index,pending={},{}
    local gui=player.gui
    for _,key in ipairs(args.api.LuaGui.attributes) do
      local ok,root=pcall(function() return gui[key] end)
      if ok and api_class(root)=="LuaGuiElement" then pending[#pending+1]=root end
    end
    local cursor=1
    while cursor<=#pending do
      local element=pending[cursor]
      cursor=cursor+1
      local index=element.index
      if not by_index[index] then
        by_index[index]=element
        for _,child in ipairs(element.children) do pending[#pending+1]=child end
      else
        assert(by_index[index]==element,"Duplicate GUI element index")
      end
    end
    local function matches(value,predicate)
      if predicate.kind=="value" then return type(value)==type(predicate.value) and value==predicate.value end
      if predicate.kind=="array" then
        if type(value)~="table" or api_class(value) then return false end
        local keys=0
        for key,item in next,value do
          if type(key)~="number" or key<1 or key>predicate.count or key~=math.floor(key) then return false end
          if not matches(item,predicate.values[key]) then return false end
          keys=keys+1
        end
        return keys==predicate.count
      end
      if type(value)~="table" and type(value)~="userdata" then return false end
      for key,child in pairs(predicate.fields) do
        local ok,item=pcall(read_property,value,key)
        if not ok or not matches(item,child) then return false end
      end
      return true
    end
    if phase==1 then
      local output={}
      for row=1,count do
        local offset=9+(row-1)*8
        local element=u32(offset+4)==1 and by_index[u32(offset)] or nil
        for step=1,args.steps do
          local predicate=args.matches[tostring(step)]
          output[#output+1]=(not predicate or (element and matches(element,predicate))) and "1" or "0"
        end
      end
      return table.concat(output)
    end
    local function preview(value,state,hint)
      return observe_value(value,args.depth,native_reference,state,hint)
    end
    local function collect(element,request)
      local value,hint=element,nil
      for _,step in ipairs(request.path) do
        assert(value~=nil,"Collection path reached nil")
        if step.property then value,hint=read_property(value,step.property,hint)
        else value,hint=read_index(value,step.index,hint) end
      end
      if type(value)=="string" then return slice_utf8(value,request.offset,request.limit) end
      local keys,total=collection_keys(value,hint)
      local state={}
      local result={value_type="collection",offset=request.offset,total=total,entries={},
        has_more=request.offset+request.limit<total,truncated=request.offset+request.limit<total}
      if request.offset+request.limit<total then result.next_offset=request.offset+request.limit end
      for index=request.offset+1,math.min(total,request.offset+request.limit) do
        local key=keys and keys[index] or index
        local entry={key=key}
        local ok,item=pcall(function()
          local child,child_hint=read_index(value,key,hint)
          return preview(child,state,child_hint)
        end)
        if not ok then entry.read_status={status="error",message=tostring(item)}
        elseif item==nil then entry.read_status={status="nil"}
        else entry.value=item end
        result.entries[#result.entries+1]=entry
      end
      if state.depth_truncated then
        result.depth_truncated=true
        result.truncated=true
      end
      return result
    end
    local result={fields=args.fields,depth=args.depth,elements={}}
    for row=1,count do
      local offset=9+(row-1)*8
      if u32(offset+4)==1 then
        local index=u32(offset)
        local element=by_index[index]
        assert(element,"Registered widget has no existing LuaGuiElement")
        local parent=element.parent
        local item={index=element.index,name=element.name,type=element.type}
        if args.properties then item.properties={} item.read_status={} end
        if parent then item.parent=parent.index end
        if args.collection then
          local ok,value=pcall(collect,element,args.collection)
          item.collection=ok and value or {read_status={status="error",message=tostring(value)}}
        elseif not args.collection_only and args.children then
          item.children={}
          for _,child in ipairs(element.children) do item.children[#item.children+1]=child.index end
        end
        local state={}
        for _,field in ipairs(args.fields) do
          local ok,value=pcall(function()
            local child,hint=read_property(element,field)
            return preview(child,state,hint)
          end)
          if not ok then item.read_status[field]={status="error",message=tostring(value)}
          elseif value==nil then item.read_status[field]={status="nil"}
          else item.properties[field]=value end
        end
        if state.depth_truncated then item.values_truncated=true end
        result.elements[tostring(index)]=item
      end
    end
    return helpers.table_to_json(result)
""".trimIndent()
