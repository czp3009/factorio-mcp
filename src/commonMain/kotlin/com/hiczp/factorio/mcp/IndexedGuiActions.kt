package com.hiczp.factorio.mcp

/** Indexed controls come from the actual game layout; completion uses authoritative state. */
internal suspend fun GameProcess.indexedGuiAction(
    kind: String,
    index: Int,
    enabled: Boolean,
    timeoutMillis: Int
): String {
    require(index > 0) { "index must be positive" }
    val setup: String
    val point: String
    val select: String
    val confirmed: String
    val result: String
    when (kind) {
        "platform_go" -> {
            setup = """
                assert(entity.type=='space-platform-hub', 'open an owned platform hub first')
                local platform=entity.surface.platform
                local schedule=assert(platform.schedule, 'the platform has no schedule')
                local count=#schedule.records
                assert($index<=count, 'index is outside the schedule')
            """.trimIndent()
            point = "schedule_go_point"
            select = "schedule_go"
            confirmed =
                "platform.valid and platform.schedule and platform.schedule.current==$index and not platform.paused"
            result = "{tick=game.tick,platform=platform.index,index=$index,paused=platform.paused}"
        }

        "platform_remove" -> {
            setup = """
                assert(entity.type=='space-platform-hub', 'open an owned platform hub first')
                local platform=entity.surface.platform
                local schedule=assert(platform.schedule, 'the platform has no schedule')
                local count=#schedule.records
                assert($index<=count, 'index is outside the schedule')
                local function removed()
                    local current=platform.schedule
                    local records=current and current.records or {}
                    if #records~=count-1 then return false end
                    for i,record in ipairs(records) do
                        if record.station~=schedule.records[i<$index and i or i+1].station then return false end
                    end
                    return true
                end
            """.trimIndent()
            point = "schedule_remove_point"
            select = "schedule_remove"
            confirmed = "platform.valid and removed()"
            result = "{tick=game.tick,platform=platform.index,removed_index=$index,remaining=count-1}"
        }

        "logistic_section" -> {
            setup = """
                local sections=assert(entity.get_logistic_sections(), 'the entity has no logistic sections')
                local count=sections.sections_count
                local section=assert(sections.get_section($index), 'section does not exist')
                assert(section.is_manual, 'automatic sections are controlled by the game')
            """.trimIndent()
            point = "logistic_point"
            select = "logistic_toggle"
            confirmed = "section.valid and section.active==$enabled"
            result = "{tick=game.tick,entity=entity.name,index=$index,active=section.active}"
        }

        else -> error("Unknown indexed game action")
    }
    val task = """
        local b=assert(__factorio_mcp_resident_v1)
        local complete=b.completion(${luaQuote(kind)})
        return {responseType=${luaQuote(kind)},ready=function() return not b.player_action and not b.research end,callback=function()
            local p=b.player()
            local entity=p.opened
            assert(entity and entity.object_name=='LuaEntity' and entity.force==p.force, 'open the owned target entity first')
            $setup
            local stage,finished=0,false
            local deadline=b.native('now')+$timeoutMillis
            local function finish(value,failed)
                if finished then return end
                finished=true;b.native('pointer_end');b.player_action=nil;complete(value,failed)
            end
            local function fail(reason) finish(reason,true) end
            b.player_action=true
            local unchanged=$confirmed
            b.observe('input',function()
                if finished then return true end
                assert(p.valid and p.connected and entity.valid, 'player or entity became unavailable')
                assert(b.native('now')<deadline, 'operation was not confirmed; inspect the game state before retrying')
                if unchanged or stage==2 then return false end
                assert(p.opened==entity, 'keep the target entity open')
                local response=b.native(stage==0 and ${luaQuote(point)} or ${luaQuote(select)},$index,count)
                if response=='waiting' then return false end
                assert(response=='submitted',response);stage=stage+1
                return false
            end,fail)
            b.observe('tick',function()
                if finished then return true end
                if (unchanged or stage==2) and ($confirmed) then
                    local result=$result
                    result.unchanged=unchanged
                    finish(result,false)
                    return true
                end
                return false
            end,fail)
        end}
    """.trimIndent()
    return submitPlayerTask(task, timeoutMillis)
}
