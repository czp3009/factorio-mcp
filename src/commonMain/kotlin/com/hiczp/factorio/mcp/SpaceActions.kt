package com.hiczp.factorio.mcp

/** Creation confirms a platform order; its starter-pack delivery can continue afterwards. */
internal suspend fun GameProcess.createPlatform(timeoutMillis: Int): String {
    val task = """
        local b=assert(__factorio_mcp_resident_v1)
        local complete=b.completion('create_platform')
        return {responseType='create_platform',ready=function() return not b.player_action and not b.research end,callback=function()
            local p=b.player()
            assert(p.force.is_space_platforms_unlocked(), 'space platforms are not unlocked')
            assert(p.controller_type==defines.controllers.remote, 'enter remote_view first')
            local previous={}
            for index in pairs(p.force.platforms) do previous[index]=true end
            local deadline=b.native('now')+$timeoutMillis
            local stage,finished=0,false
            local function finish(value,failed)
                if finished then return end
                finished=true;b.player_action=nil;complete(value,failed)
            end
            local function fail(reason) finish(reason,true) end
            b.player_action=true
            b.observe('input',function()
                if finished then return true end
                assert(p.valid and p.connected, 'player became unavailable')
                assert(b.native('now')<deadline, 'platform order was not confirmed; inspect_space before retrying')
                local result
                if stage==0 then
                    if p.opened_gui_type~=defines.gui_type.none then
                        result=b.native('close_gui')
                        assert(result=='submitted' or result=='waiting',result)
                        return false
                    end
                    result=b.native('platform_new')
                elseif stage==1 then result=b.native('platform_confirm')
                else return false end
                if result=='waiting' then return false end
                assert(result=='submitted',result);stage=stage+1
                return false
            end,fail)
            b.observe('tick',function()
                if finished then return true end
                if stage==2 then
                    for index,platform in pairs(p.force.platforms) do
                        if not previous[index] then
                            local starter=platform.starter_pack
                            finish({tick=game.tick,index=index,name=platform.name,
                                space_location=platform.space_location and platform.space_location.name or nil,
                                starter_pack=starter and {name=starter.name.name,quality=starter.quality.name} or nil},false)
                            return true
                        end
                    end
                end
                return false
            end,fail)
        end}
    """.trimIndent()
    return submitPlayerTask(task, timeoutMillis)
}

/** The ordinary silo action validates cargo, orbit and player-transport constraints. */
internal suspend fun GameProcess.launchRocket(
    platformIndex: Int,
    transportPlayer: Boolean,
    timeoutMillis: Int
): String {
    require(platformIndex > 0) { "platform must be a positive index from inspect_space" }
    val passengerChecks = if (transportPlayer) """
        assert(p.character and platform.hub and p.physical_surface==silo.surface, 'player transport requires a character on this planet and a built platform')
        for _,id in ipairs{defines.inventory.character_main,defines.inventory.character_ammo,defines.inventory.character_trash} do
            local inventory=p.character.get_inventory(id)
            assert(not inventory or inventory.is_empty(), 'empty the character main inventory, ammunition and trash before boarding')
        end
        assert(not p.cursor_stack or not p.cursor_stack.valid_for_read, 'clear the cursor before boarding')
    """.trimIndent() else ""
    val task = """
        local b=assert(__factorio_mcp_resident_v1)
        local complete=b.completion('rocket_launch')
        return {responseType='rocket_launch',ready=function() return not b.player_action and not b.research end,callback=function()
            local p=b.player()
            local silo=p.opened
            assert(silo and silo.object_name=='LuaEntity' and silo.type=='rocket-silo' and silo.force==p.force and p.can_reach_entity(silo), 'open a reachable friendly rocket silo first')
            assert(silo.rocket_silo_status==defines.rocket_silo_status.rocket_ready, 'the silo rocket is not ready; inspect it before launching')
            local platform=assert(p.force.platforms[$platformIndex], 'platform does not belong to this force')
            assert(not platform.hidden and platform.scheduled_for_deletion==0, 'platform is unavailable')
            local planet=silo.surface.planet
            assert(planet and platform.space_location and platform.space_location.name==planet.name, 'platform must be in orbit above this silo')
            $passengerChecks
            local deadline=b.native('now')+$timeoutMillis
            local submitted,finished=false,false
            local function finish(value,failed)
                if finished then return end
                finished=true;b.player_action=nil;complete(value,failed)
            end
            local function fail(reason) finish(reason,true) end
            b.player_action=true
            b.observe('input',function()
                if finished then return true end
                assert(p.valid and p.connected and silo.valid and platform.valid, 'player, silo or platform became unavailable')
                assert(b.native('now')<deadline, 'rocket launch was not confirmed; inspect silo and platform before retrying')
                if not submitted then
                    assert(p.opened==silo, 'keep the target silo open')
                    local result=b.native('rocket_launch',$platformIndex,${if (transportPlayer) 1 else 0})
                    if result=='waiting' then return false end
                    assert(result=='submitted',result);submitted=true
                end
                return false
            end,fail)
            b.observe('tick',function()
                if finished then return true end
                if submitted then
                    local status=silo.rocket_silo_status
                    local phases=defines.rocket_silo_status
                    if status==phases.launch_starting or status==phases.launch_started or status==phases.engine_starting
                        or status==phases.arms_retract or status==phases.rocket_flying then
                        finish({tick=game.tick,platform=$platformIndex,transport_player=$transportPlayer,accepted=true},false)
                        return true
                    end
                end
                return false
            end,fail)
        end}
    """.trimIndent()
    return submitPlayerTask(task, timeoutMillis)
}
