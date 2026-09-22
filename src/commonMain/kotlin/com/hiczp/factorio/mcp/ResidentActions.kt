package com.hiczp.factorio.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** The world owns every phase, including confirmation and local window cleanup. */
internal suspend fun GameProcess.submitResearch(name: String, timeoutMillis: Int): String {
    require(name.isNotBlank()) { "Technology name is required" }
    val description = """
        local bridge = assert(__factorio_mcp_resident_v1, 'world bridge is unavailable')
        local complete = bridge.completion('research')
        return {responseType='research', ready=function() return not bridge.research and not bridge.player_action end, callback=function()
            local p=assert(__factorio_mcp_resident_v1).player()
            local name = ${luaQuote(name)}
            local technology = assert(p.force.technologies[name], 'technology not found')
            assert(technology.enabled and not technology.researched, 'technology is disabled or already researched')
            for _, prerequisite in pairs(technology.prerequisites) do
                assert(prerequisite.researched, string.format('research prerequisite missing: %s',prerequisite.name))
            end
            assert(not game.tick_paused, 'the game is paused')
            local deadline = bridge.native('now') + $timeoutMillis
            local force = p.force
            local function selected()
                for index, entry in ipairs(force.research_queue or {}) do
                    local queued = type(entry)=='string' and entry or entry.name
                    if queued==name then return {technology=name, force=force.name, tick=game.tick, queue_position=index} end
                end
                if force.current_research and force.current_research.name==name then
                    return {technology=name, force=force.name, tick=game.tick, queue_position=1}
                end
            end
            local existing = selected()
            if existing then
                bridge.research = true
                bridge.observe('tick', function()
                    bridge.research = nil
                    existing.unchanged = true
                    complete(existing, false)
                    return true
                end, function(reason) bridge.research=nil; complete(reason, true) end)
                return
            end
            p.open_technology_gui(name)
            bridge.research = true
            local finished = false
            local function finish(value, failed)
                if finished then return end
                finished = true
                bridge.observe('input', function()
                    bridge.native('close_research')
                    bridge.research = nil
                    complete(value, failed)
                    return true
                end, function(reason)
                    bridge.research = nil
                    complete(reason, true)
                end)
            end
            local function fail(reason) finish(reason, true) end
            local function validate()
                assert(p.valid and p.connected and p.force==force, 'player or force changed')
                assert(bridge.native('now') < deadline, 'research confirmation timed out; inspect current state before retrying')
            end
            -- Input phases continue during a paused simulation, so deadlines still expire.
            bridge.observe('input', function()
                if finished then return true end
                validate()
                return false
            end, fail)
            bridge.observe('input', function()
                if finished then return true end
                validate()
                if bridge.native('research_ready') ~= 'ready' then return false end
                local result = bridge.native('research', name)
                assert(result=='submitted', result)
                bridge.observe('tick', function()
                    if finished then return true end
                    validate()
                    local result = selected()
                    if result then
                        finish(result, false)
                        return true
                    end
                    return false
                end, fail)
                return true
            end, fail)
        end}
    """.trimIndent()
    val response = Json.parseToJsonElement(submitTask(description, timeoutMillis)).jsonObject
    return response.getValue("value").toString()
}
