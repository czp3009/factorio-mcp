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
          local found=surface.find_entities_filtered{area=bounds,limit=maximum+1}
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
              local quality=entity.quality.name
              local key=entity.name .. "\0" .. entity.type .. "\0" .. quality
              local group=groups[key]
              if not group then
                group={name=entity.name,type=entity.type,quality=identity(entity.quality),count=0}
                groups[key]=group
                keys[#keys+1]=key
              end
              group.count=group.count+1
              if entity.type=="resource" then group.amount=(group.amount or 0)+entity.amount end
              record.entities_observed=record.entities_observed+1
            end
          end
          table.sort(keys)
          for _,key in ipairs(keys) do record.entity_groups[#record.entity_groups+1]=groups[key] end
        end
        truncated=truncated or record.truncated
        objects[#objects+1]=record
      end
    end
    """
        .trimIndent()
