package com.hiczp.factorio.mcp

internal suspend fun GameProcess.equipment(action: String, x: Int, y: Int, timeoutMillis: Int): String {
    require(
        action in setOf(
            "put",
            "take"
        ) && x >= 0 && y >= 0
    ) { "Specify put/take and nonnegative equipment-grid coordinates" }
    val task = """
        local b=assert(__factorio_mcp_resident_v1)
        local complete=b.completion('equipment')
        return {responseType='equipment',ready=function() return not b.player_action and not b.research end,callback=function()
            local p=assert(__factorio_mcp_resident_v1).player()
            local opened=p.opened
            assert(p.character and opened and opened.object_name=='LuaEquipmentGrid', 'open an armor equipment grid first')
            local grid=opened
            local character,surface=p.character,p.surface
            local position={x=$x,y=$y}
            assert(position.x<grid.width and position.y<grid.height, 'position is outside the equipment grid')
            local held=p.cursor_stack
            local cellWidth,cellHeight=1,1
            ${
        if (action == "put") """
            assert(held.valid_for_read, 'hold equipment first')
            local prototype=assert(held.prototype.place_as_equipment_result, 'held item is not equipment')
            local expected=prototype.name
            local before=held.count
            cellWidth,cellHeight=prototype.shape.width,prototype.shape.height
            assert(position.x+prototype.shape.width<=grid.width and position.y+prototype.shape.height<=grid.height, 'equipment does not fit here')
            for dx=0,prototype.shape.width-1 do for dy=0,prototype.shape.height-1 do
                assert(not grid.get{x=position.x+dx,y=position.y+dy}, 'equipment would overlap an occupied cell')
            end end
            """.trimIndent() else """
            assert(not held.valid_for_read and not p.cursor_ghost, 'clear the cursor first')
            local target=assert(grid.get(position), 'no equipment at that position')
            local expected=target.name
            """.trimIndent()
    }
            local deadline=b.native('now')+$timeoutMillis
            local stage,finished=0,false
            local pointTick
            local function finish(value,failed)
                if finished then return end
                finished=true;b.native('pointer_end');b.player_action=nil;complete(value,failed)
            end
            local function fail(reason) finish(reason,true) end
            local function validate()
                assert(p.valid and p.connected and p.character==character and p.surface==surface, 'player, character or surface changed')
                local current=p.opened
                assert(grid.valid and current and current.object_name=='LuaEquipmentGrid' and current.unique_id==grid.unique_id, 'opened equipment grid changed')
                assert(b.native('now')<deadline, 'equipment operation timed out; inspect the grid before retrying')
            end
            b.player_action=true
            b.observe('input',function()
                if finished then return true end
                validate()
                if stage==0 then
                    local result=b.native('grid_point',(position.x+cellWidth/2)/grid.width,(position.y+cellHeight/2)/grid.height,{x=position.x+cellWidth/2,y=position.y+cellHeight/2})
                    if result=='waiting' then return false end
                    assert(result=='submitted',result)
                    pointTick=game.tick;stage=1
                elseif stage==1 and game.tick>pointTick then
                    local result=b.native('grid_pick',(position.x+cellWidth/2)/grid.width,(position.y+cellHeight/2)/grid.height)
                    if result=='waiting' then return false end
                    assert(result=='submitted',result);stage=2
                end
                return false
            end,fail)
            b.observe('tick',function()
                if finished then return true end
                validate()
                local actual=grid.get(position)
                if stage==2 and ${if (action == "put") "actual and actual.name==expected and (not held.valid_for_read or held.count<before)" else "not target.valid and held.valid_for_read"} then
                    finish({action=${luaQuote(action)},equipment=expected,position=position,tick=game.tick},false)
                    return true
                end
                return false
            end,fail)
        end}
    """.trimIndent()
    return submitPlayerTask(task, timeoutMillis)
}
