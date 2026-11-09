package com.hiczp.factorio.mcp

/** Native API objects are userdata. Ordinary tables are observed through stored entries only. */
internal val valueObservationLua = """
    local function api_class(value,hint)
      if type(value)~="userdata" then return nil,nil end
      local ok,name=pcall(function() return value.object_name end)
      if ok and type(name)=="string" then
        local metadata=args.api and args.api[name]
        local declared=hint and hint.class
        if not metadata and declared and args.api then metadata=args.api[declared] end
        return name,metadata,metadata and (args.api[name] and name or declared) or nil
      end
      return nil,nil
    end
    local function admitted(values,name)
      for _,value in ipairs(values or {}) do if value==name then return true end end
      return false
    end
    local function child_type(hint,key)
      return hint and (hint.fields and hint.fields[key] or hint.element) or nil
    end
    local function read_property(object,key,hint)
      local class,metadata=api_class(object,hint)
      if class then
        assert(metadata and admitted(metadata.attributes,key),"Attribute is not an admitted passive read on this object: "..key)
        return object[key],metadata.attribute_types and metadata.attribute_types[key]
      end
      assert(type(object)=="table","Property target is not an object")
      return rawget(object,key),child_type(hint,key)
    end
    local function read_index(object,key,hint)
      local class,metadata=api_class(object,hint)
      if class then
        assert(metadata and metadata.indexed,"Object has no readable index operator")
        return object[key],hint and hint.element or metadata.index_type
      end
      assert(type(object)=="table","Index target is not a table")
      return rawget(object,key),child_type(hint,key)
    end
    local function native_reference(value,hint)
      local class,metadata,declared=api_class(value,hint)
      local result={object_name=class,observation="reference",members_available=args.api and metadata~=nil}
      if declared and declared~=class then result.api_class=declared end
      for _,field in ipairs{"name","index","id","unit_number","valid"} do
        if metadata and admitted(metadata.attributes,field) then
          local ok,item=pcall(function() return value[field] end)
          local kind=type(item)
          if ok and (kind=="string" or kind=="boolean" or kind=="number" and item==item and item~=math.huge and item~=-math.huge) then
            result[field]=item
          end
        end
      end
      return result
    end
    local function collection_keys(object,hint)
      local class,metadata=api_class(object,hint)
      assert(not class and type(object)=="table" or metadata and metadata.indexed,"Target has no readable collection")
      if metadata and metadata.length and class~="LuaCustomTable" then
        local total=#object
        assert(total>=0 and total<=2147483647,"Collection length exceeds integer representation")
        return nil,total
      end
      local keys={}
      local function key(value)
        local kind=type(value)
        assert(kind=="string" or kind=="number" and value==value and value~=math.huge and value~=-math.huge,"Unsupported collection key")
        keys[#keys+1]=value
      end
      if class then
        for value in pairs(object) do key(value) end
      else
        for value in next,object do key(value) end
      end
      table.sort(keys,function(a,b) if type(a)==type(b) then return a<b end return type(a)<type(b) end)
      return keys,#keys
    end
    local function observe_value(value,max_depth,reference,state,hint)
      local active,pending={},{}
      local omitted=false
      local function copy(item,depth,item_hint)
        local kind=type(item)
        if kind=="nil" or kind=="string" or kind=="boolean" then return item end
        if kind=="number" then
          if item~=item or item==math.huge or item==-math.huge then return {observation="nonfinite",value=tostring(item)} end
          return item
        end
        if api_class(item,item_hint) then return reference(item,item_hint) end
        if kind~="table" then return {observation="unsupported",value_type=kind} end
        if active[item] then return {observation="reference",value_type="table",reason="cycle"} end
        if next(item)==nil then return {} end
        if depth>=max_depth then
          omitted=true
          return {observation="reference",value_type="table",reason="depth_bound",children_truncated=true}
        end
        local output={}
        active[item]=true
        pending[#pending+1]={source=item,output=output,depth=depth,hint=item_hint}
        return output
      end
      local result=copy(value,0,hint)
      while #pending>0 do
        local frame=pending[#pending]
        local key,item=next(frame.source,frame.key)
        if key==nil then
          active[frame.source]=nil
          pending[#pending]=nil
        else
          local kind=type(key)
          assert(kind=="string" or kind=="number" and key==key and key~=math.huge and key~=-math.huge,"Unsupported table key")
          frame.key=key
          frame.output[key]=copy(item,frame.depth+1,child_type(frame.hint,key))
        end
      end
      if omitted then state.depth_truncated=true end
      return result
    end
""".trimIndent()
