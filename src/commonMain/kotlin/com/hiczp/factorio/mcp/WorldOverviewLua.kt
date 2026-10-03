package com.hiczp.factorio.mcp

/** Fixed overview branch, inserted into the fixed world reader before compilation. */
internal val worldOverviewLua =
    """
    local area=selection.area
    local left,top=area.left_top.x,area.left_top.y
    local right,bottom=area.right_bottom.x,area.right_bottom.y
    local width,height=right-left,bottom-top
    assert(width>0 and height>0 and width<=4096 and height<=4096,
      "Overview exceeds 4096 tiles per side; select an explicit bounded area")
    if args.detail=="entities" then
      extra.area=area
      extra.viewport=viewport
      extra.render_mode=player.render_mode
      extra.observation="live_world_entities"
      extra.entity_assignment="collision_in_area"
      local found=surface.find_entities_filtered{area=area,name=selection.name,type=selection.type,limit=limit+1}
      candidates=#found
      truncated=#found>limit
      for index=1,math.min(#found,limit) do objects[#objects+1]=describe(found[index]) end
    else
    local cell=args.cell_size or math.max(32,math.ceil(width/16),math.ceil(height/16))
    local columns,rows=math.ceil(width/cell),math.ceil(height/cell)
    assert(columns*rows<=256, "Overview exceeds 256 cells; increase cell_size or narrow the area")
    extra.area=area
    extra.cell_size=cell
    extra.columns=columns
    extra.rows=rows
    extra.viewport=viewport
    extra.render_mode=player.render_mode
    extra.observation="live_world_aggregation"
    extra.entity_assignment="center_in_cell"
    extra.coverage_basis="intersecting_chunks"
    local remaining_candidates=4096
    local group_fields=args.group_by
    local aggregate_fields=args.aggregates
    local requested,seen={},{}
    local function request(field)
      if not seen[field] then requested[#requested+1]=field; seen[field]=true end
    end
    for _,field in ipairs(group_fields) do request(field) end
    for _,aggregate in ipairs(aggregate_fields) do request(aggregate.field) end
    local function finite(value) return value==value and value~=math.huge and value~=-math.huge end
    local function canonical(value)
      local kind=type(value)
      if kind=="nil" then return "nil" end
      if kind=="number" then return value==0 and "number:0" or "number:" .. string.format("%.17g",value) end
      if kind~="table" then
        local text=tostring(value)
        return kind .. #text .. ":" .. text
      end
      local keys={}
      for key in pairs(value) do keys[#keys+1]=key end
      table.sort(keys,function(a,b) return canonical(a)<canonical(b) end)
      local parts={"table:" .. #keys .. ":"}
      for _,key in ipairs(keys) do
        local name,item=canonical(key),canonical(value[key])
        parts[#parts+1]=#name .. ":" .. name .. #item .. ":" .. item
      end
      return table.concat(parts)
    end
    for row=0,rows-1 do
      for column=0,columns-1 do
        local x,y=left+column*cell,top+row*cell
        local x2,y2=math.min(right,x+cell),math.min(bottom,y+cell)
        local bounds={left_top={x=x,y=y},right_bottom={x=x2,y=y2}}
        local record={row=row,column=column,area=bounds,entity_groups={},entities_observed=0,
          coverage={chunks=0,generated=0,charted=0,visible=0}}
        local coverage=record.coverage
        for cy=math.floor(y/32),math.ceil(y2/32)-1 do
          for cx=math.floor(x/32),math.ceil(x2/32)-1 do
            local observed=visibility{x=cx*32,y=cy*32}
            coverage.chunks=coverage.chunks+1
            for _,name in ipairs{"generated","charted","visible"} do
              if observed[name] then coverage[name]=coverage[name]+1 end
            end
          end
        end
        local center={x=math.floor((x+x2)/2),y=math.floor((y+y2)/2)}
        record.tile_sample={position=center,availability="ungenerated"}
        if visibility(center).generated then
          record.tile_sample={position=center,name=surface.get_tile(center).name,availability="generated"}
        end
        if remaining_candidates<=1 then
          record.entity_scan="not_scanned"
          record.truncated=true
        else
          local maximum=math.min(limit,remaining_candidates-1)
          local found=surface.find_entities_filtered{area=bounds,name=selection.name,type=selection.type,limit=maximum+1}
          record.candidates_observed=#found
          candidates=candidates+#found
          remaining_candidates=math.max(0,remaining_candidates-#found)
          record.truncated=#found>maximum
          record.entity_scan=record.truncated and "partial" or "complete"
          local groups,keys={},{}
          for index=1,math.min(maximum,#found) do
            local entity=found[index]
            local p=entity.position
            if p.x>=x and p.x<x2 and p.y>=y and p.y<y2 then
              local values,statuses={},{}
              for _,field in ipairs(requested) do
                local ok,value=pcall(function() return project(entity[field],0) end)
                if not ok then statuses[field]={status="error",message=tostring(value)}
                elseif value==nil then statuses[field]={status="nil"}
                elseif type(value)=="number" and not finite(value) then statuses[field]={status="non_finite"}
                else values[field]=value end
              end
              if remaining<0 then record.truncated=true; record.entity_scan="partial"; break end
              local attributes,read_status={},{}
              for _,field in ipairs(group_fields) do
                attributes[field]=values[field]
                read_status[field]=statuses[field]
              end
              local key=canonical({attributes=attributes,read_status=read_status})
              local group=groups[key]
              if not group then
                group={attributes=attributes,read_status=read_status,count=0,aggregates={}}
                for _,aggregate in ipairs(aggregate_fields) do
                  group.aggregates[#group.aggregates+1]={operation=aggregate.operation,field=aggregate.field,
                    numeric_values=0,nil_values=0,read_errors=0,non_numeric_values=0,non_finite_values=0}
                end
                groups[key]=group
                keys[#keys+1]=key
              end
              group.count=group.count+1
              for _,aggregate in ipairs(group.aggregates) do
                local value,status=values[aggregate.field],statuses[aggregate.field]
                if status then
                  local count=status.status=="nil" and "nil_values" or
                    status.status=="non_finite" and "non_finite_values" or "read_errors"
                  aggregate[count]=aggregate[count]+1
                elseif type(value)~="number" then aggregate.non_numeric_values=aggregate.non_numeric_values+1
                else
                  if aggregate.numeric_values==0 then aggregate.value=value
                  elseif aggregate.operation=="sum" then aggregate.value=aggregate.value+value
                  elseif aggregate.operation=="min" then aggregate.value=math.min(aggregate.value,value)
                  else aggregate.value=math.max(aggregate.value,value) end
                  aggregate.numeric_values=aggregate.numeric_values+1
                end
              end
              record.entities_observed=record.entities_observed+1
            end
          end
          table.sort(keys)
          for _,key in ipairs(keys) do
            local group=groups[key]
            for _,aggregate in ipairs(group.aggregates) do
              if aggregate.value and not finite(aggregate.value) then
                aggregate.value=nil
                aggregate.value_non_finite=true
              end
            end
            record.entity_groups[#record.entity_groups+1]=group
          end
        end
        truncated=truncated or record.truncated
        objects[#objects+1]=record
      end
    end
    end
    """
        .trimIndent()
