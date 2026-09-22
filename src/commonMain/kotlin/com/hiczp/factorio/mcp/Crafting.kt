package com.hiczp.factorio.mcp

internal suspend fun GameProcess.craft(recipe: String, count: Int, timeoutMillis: Int): String {
    require(recipe.isNotBlank() && count in 1..100) { "A recipe name and count in 1..100 are required" }
    val task = """
        local b=assert(__factorio_mcp_resident_v1)
        local complete=b.completion('craft')
        return {responseType='craft',ready=function() return not b.player_action and not b.research end,callback=function()
            local p=assert(__factorio_mcp_resident_v1).player()
            local name=${luaQuote(recipe)}
            local recipe=assert(p.force.recipes[name], 'recipe not found')
            assert(recipe.enabled, 'recipe is not enabled')
            assert(p.get_craftable_count(name)>=$count, 'insufficient materials or recipe is not hand craftable')
            local deadline=b.native('now')+$timeoutMillis
            local finished,opened,selected,scrolled,pointed=false,false,false,false,false
            local submitted,accepted=0,0
            local removeEvent
            b.player_action=true
            local function finish(value,failed)
                if finished then return end
                finished=true
                b.native('pointer_end')
                if removeEvent then removeEvent() end
                b.observe('input',function()
                    b.native('craft_reset')
                    b.player_action=nil
                    complete(value,failed)
                    return true
                end,function(reason)
                    b.player_action=nil
                    complete(reason,true)
                end)
            end
            local function fail(reason) finish(reason,true) end
            removeEvent=b.event(defines.events.on_pre_player_crafted_item,function(event)
                if event.player_index==p.index and event.recipe.name==name then
                    accepted=accepted+event.queued_count
                    if accepted>=$count then finish({recipe=name,queued=accepted,tick=event.tick},false);return true end
                end
                return false
            end,fail)
            b.observe('input',function()
                if finished then return true end
                assert(p.valid and p.connected and p.character, 'player became unavailable')
                assert(b.native('now')<deadline, 'crafting confirmation timed out; inspect crafting queue before retrying')
                if not opened then
                    if p.opened_gui_type~=defines.gui_type.controller then
                        local result=b.native('tap','open-character-gui',p.position)
                        assert(result=='submitted',result)
                    end
                    opened=true
                    return false
                end
                if p.opened_gui_type~=defines.gui_type.controller then return false end
                if not selected then
                    local result=b.native('craft_prepare')
                    if result=='waiting' then return false end
                    assert(result=='selected',result)
                    selected=true
                    return false
                end
                if not scrolled then
                    local result=b.native('craft_scroll',name)
                    if result=='waiting' then return false end
                    assert(result=='submitted',result)
                    scrolled=true
                    return false
                end
                if not pointed then
                    local result=b.native('craft_point',name)
                    if result=='waiting' then return false end
                    assert(result=='submitted',result)
                    pointed=true
                    return false
                end
                if submitted<$count then
                    local result=b.native('craft',name)
                    if result=='waiting' then return false end
                    assert(result=='submitted',result)
                    submitted=submitted+1
                end
                return false
            end,fail)
        end}
    """.trimIndent()
    return submitPlayerTask(task, timeoutMillis)
}
