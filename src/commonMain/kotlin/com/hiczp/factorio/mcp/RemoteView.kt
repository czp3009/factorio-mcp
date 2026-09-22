package com.hiczp.factorio.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive

/** View changes are ordinary synchronized player actions; physical location stays game-owned. */
internal suspend fun GameProcess.remoteView(args: JsonObject, timeoutMillis: Int): String {
    val action = args["action"]?.jsonPrimitive?.content ?: "enter"
    require(action in setOf("enter", "exit")) { "action must be enter or exit" }
    val surface = args["surface"]?.jsonPrimitive?.content
    val position = queryPosition(args)
    val zoom = args["zoom"]?.jsonPrimitive?.double ?: 1.0
    require(zoom.isFinite() && zoom > 0) { "zoom must be finite and positive" }
    require(action != "exit" || args.keys.all { it == "action" }) { "exit does not accept view coordinates" }
    val task = """
        local b=assert(__factorio_mcp_resident_v1)
        local complete=b.completion('remote_view')
        return {responseType='remote_view',ready=function() return not b.player_action and not b.research end,callback=function()
            local p=b.player()
            local destination=${surface?.let { "assert(game.surfaces[${luaQuote(it)}], 'surface does not exist')" } ?: "p.surface"}
            local position=$position
            assert(not p.force.get_surface_hidden(destination), 'surface is hidden from this force')
            local platform=destination.platform
            assert(not platform or platform.force==p.force and not platform.hidden, 'platform is not accessible to this force')
            local exiting=${action == "exit"}
            if exiting then
                assert(not p.physical_surface.platform, 'cannot exit remote view while physically on a platform; drop to a planet first')
            end
            local function snapshot()
                return {tick=game.tick,controller_type=p.controller_type,surface=p.surface.name,position=p.position,
                    physical_surface=p.physical_surface.name,physical_position=p.physical_position,
                    render_mode=p.render_mode,zoom=p.zoom}
            end
            local function reached()
                if exiting then return p.controller_type~=defines.controllers.remote end
                return p.controller_type==defines.controllers.remote and p.surface==destination
                    and math.abs(p.position.x-position.x)<0.01 and math.abs(p.position.y-position.y)<0.01
            end
            local deadline=b.native('now')+$timeoutMillis
            local finished,submitted=false,false
            local function finish(value,failed)
                if finished then return end
                finished=true;b.native('pointer_end');b.player_action=nil;complete(value,failed)
            end
            local function fail(reason) finish(reason,true) end
            local function validate()
                assert(p.valid and p.connected and destination.valid, 'player or destination became unavailable')
                assert(b.native('now')<deadline, 'remote view was not confirmed; inspect player state before retrying')
            end
            b.player_action=true
            b.observe('input',function()
                if finished then return true end
                validate()
                if not submitted then
                    if exiting and reached() then submitted=true;return false end
                    local result
                    if exiting then result=b.native('tap','toggle-map',p.position)
                    else result=b.native('remote_view',p,position,destination.index,$zoom) end
                    assert(result=='submitted',result)
                    submitted=true
                end
                return false
            end,fail)
            b.observe('tick',function()
                if finished then return true end
                validate()
                if submitted and reached() then finish(snapshot(),false);return true end
                return false
            end,fail)
        end}
    """.trimIndent()
    return submitPlayerTask(task, timeoutMillis)
}
