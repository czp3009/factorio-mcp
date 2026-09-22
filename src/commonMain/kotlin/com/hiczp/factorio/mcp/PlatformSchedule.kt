package com.hiczp.factorio.mcp

/** Station selection submits the game's ordinary synchronized schedule change. */
internal suspend fun GameProcess.appendPlatformStop(location: String, timeoutMillis: Int): String {
    require(location.isNotBlank()) { "location is required" }
    val task = """
        local b=assert(__factorio_mcp_resident_v1)
        local complete=b.completion('platform_schedule')
        return {responseType='platform_schedule',ready=function() return not b.player_action and not b.research end,callback=function()
            local p=b.player()
            local hub=p.opened
            assert(hub and hub.object_name=='LuaEntity' and hub.type=='space-platform-hub' and hub.force==p.force, 'open an owned platform hub first')
            local platform=hub.surface.platform
            local location=${luaQuote(location)}
            assert(prototypes.space_location[location] and p.force.is_space_location_unlocked(location), 'space location is unavailable')
            local schedule=platform.schedule
            local count=schedule and #schedule.records or 0
            local deadline=b.native('now')+$timeoutMillis
            local stage,choice,finished=0,nil,false
            local function finish(value,failed)
                if finished then return end
                finished=true;b.native('pointer_end');b.player_action=nil;complete(value,failed)
            end
            local function fail(reason) finish(reason,true) end
            b.player_action=true
            b.observe('input',function()
                if finished then return true end
                assert(p.valid and p.connected and platform.valid and hub.valid, 'player or platform became unavailable')
                assert(b.native('now')<deadline, 'schedule change was not confirmed; inspect_space before retrying')
                if stage==3 then return false end
                assert(p.opened==hub, 'keep the platform hub open')
                local result
                if stage==0 then result=b.native('schedule_prepare')
                elseif stage==1 then
                    for index=1,b.native('station_count') do
                        local label=b.native('station_label',index)
                        if string.find(label,string.format('[planet=%s]',location),1,true)
                            or string.find(label,string.format('[space-location=%s]',location),1,true) then choice=index;break end
                    end
                    assert(choice, 'destination is not offered by the game')
                    result=b.native('station_point',choice)
                else result=b.native('station_choose',choice) end
                if result=='waiting' then return false end
                assert(result=='submitted',result);stage=stage+1
                return false
            end,fail)
            b.observe('tick',function()
                if finished then return true end
                if stage==3 then
                    local current=platform.schedule
                    local records=current and current.records
                    if records and #records==count+1 and records[count+1].station==location then
                        finish({tick=game.tick,platform=platform.index,index=count+1,record=records[count+1]},false)
                        return true
                    end
                end
                return false
            end,fail)
        end}
    """.trimIndent()
    return submitPlayerTask(task, timeoutMillis)
}

internal suspend fun GameProcess.setPlatformPaused(paused: Boolean, timeoutMillis: Int): String {
    val task = """
        local b=assert(__factorio_mcp_resident_v1)
        local complete=b.completion('platform_mode')
        return {responseType='platform_mode',ready=function() return not b.player_action and not b.research end,callback=function()
            local p=b.player()
            local hub=p.opened
            assert(hub and hub.object_name=='LuaEntity' and hub.type=='space-platform-hub' and hub.force==p.force, 'open an owned platform hub first')
            local platform=hub.surface.platform
            if platform.paused==$paused then
                b.observe('tick',function()
                    complete({platform=platform.index,paused=$paused,unchanged=true},false)
                    return true
                end)
                return
            end
            local stage,finished=0,false
            local deadline=b.native('now')+$timeoutMillis
            local function finish(value,failed)
                if finished then return end
                finished=true;b.native('pointer_end');b.player_action=nil;complete(value,failed)
            end
            local function fail(reason) finish(reason,true) end
            b.player_action=true
            b.observe('input',function()
                if finished then return true end
                assert(p.valid and p.connected and platform.valid and hub.valid, 'player or platform became unavailable')
                assert(b.native('now')<deadline, 'platform mode was not confirmed; inspect_space before retrying')
                if stage==2 then return false end
                assert(p.opened==hub, 'keep the platform hub open')
                local result=b.native(stage==0 and 'platform_switch_point' or 'platform_switch_toggle')
                if result=='waiting' then return false end
                assert(result=='submitted',result);stage=stage+1
                return false
            end,fail)
            b.observe('tick',function()
                if finished then return true end
                if stage==2 and platform.paused==$paused then
                    finish({tick=game.tick,platform=platform.index,paused=platform.paused},false)
                    return true
                end
                return false
            end,fail)
        end}
    """.trimIndent()
    return submitPlayerTask(task, timeoutMillis)
}
