package com.hiczp.factorio.mcp

/** Use the game's passenger destination selector and wait for physical arrival. */
internal suspend fun GameProcess.landPlayer(timeoutMillis: Int): String {
    val task = """
        local b=assert(__factorio_mcp_resident_v1)
        local complete=b.completion('planet_landing')
        return {responseType='planet_landing',ready=function() return not b.player_action and not b.research end,callback=function()
            local p=b.player()
            local platform=assert(p.physical_surface.platform, 'the character must be aboard a platform')
            local location=assert(platform.space_location, 'the platform must be in orbit')
            assert(game.planets[location.name], 'this space location has no planet')
            local destination=location.name
            local deadline=b.native('now')+$timeoutMillis
            local stage,finished=0,false
            local function finish(value,failed)
                if finished then return end
                finished=true;b.native('pointer_end');b.player_action=nil;complete(value,failed)
            end
            local function fail(reason) finish(reason,true) end
            b.player_action=true
            b.observe('input',function()
                if finished then return true end
                assert(p.valid and p.connected and p.character, 'player became unavailable')
                assert(b.native('now')<deadline, 'landing was not confirmed; inspect the physical player position before retrying')
                if stage==3 then return false end
                local result
                if stage==0 then
                    if p.opened_gui_type~=defines.gui_type.none then
                        result=b.native('close_gui')
                        assert(result=='submitted' or result=='waiting',result)
                        return false
                    end
                    result=b.native('drop_point')
                elseif stage==1 then result=b.native('drop_open')
                else
                    local count=b.native('drop_count')
                    if count==0 then return false end
                    local choice
                    for index=1,count do
                        if string.find(b.native('drop_label',index),string.format('[planet=%s]',destination),1,true) then choice=index;break end
                    end
                    assert(choice, 'the game does not offer landing on this planet')
                    result=b.native('drop_choose',choice)
                end
                if result=='waiting' then return false end
                assert(result=='submitted',result);stage=stage+1
                return false
            end,fail)
            b.observe('tick',function()
                if finished then return true end
                local planet=p.physical_surface.planet
                if stage==3 and planet and planet.name==destination and p.character and not p.character.cargo_pod then
                    finish({tick=game.tick,planet=destination,surface=p.physical_surface.name,position=p.physical_position},false)
                    return true
                end
                return false
            end,fail)
        end}
    """.trimIndent()
    return submitPlayerTask(task, timeoutMillis)
}
