package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*

/** Semantic actions use normal game controls; confirmation reads the synchronized simulation. */
internal suspend fun GameProcess.basicAction(kind: String, args: JsonObject, timeoutMillis: Int): String {
    val x = args["x"]?.jsonPrimitive?.double
    val y = args["y"]?.jsonPrimitive?.double
    require((x == null) == (y == null)) { "Specify both x and y" }
    require(x == null || x.isFinite() && y!!.isFinite()) { "Coordinates must be finite" }
    val position = if (x == null) "p.position" else "{x=$x,y=$y}"
    val setup: String
    val control: String
    val confirmed: String
    when (kind) {
        "move" -> {
            val direction = args.getValue("direction").jsonPrimitive.content
            require(direction in setOf("up", "right", "down", "left")) { "direction must be up, right, down or left" }
            control = "move-$direction"
            setup = """
                assert(p.character and not p.driving, 'walking requires a character outside a vehicle')
                local before=p.position
            """.trimIndent()
            confirmed = "p.position.x~=before.x or p.position.y~=before.y"
        }

        "drive" -> {
            val direction = args.getValue("direction").jsonPrimitive.content
            val controls =
                mapOf("forward" to "move-up", "backward" to "move-down", "left" to "move-left", "right" to "move-right")
            control = controls[direction] ?: error("direction must be forward, backward, left or right")
            setup = """
                assert(p.driving and p.vehicle and p.vehicle.type~='locomotive', 'enter a car or tank first')
                local vehicle=p.vehicle
                local before,orientation=vehicle.position,vehicle.orientation
            """.trimIndent()
            confirmed =
                "vehicle.valid and (vehicle.position.x~=before.x or vehicle.position.y~=before.y or vehicle.orientation~=orientation) and p.riding_state.acceleration==defines.riding.acceleration.nothing and p.riding_state.direction==defines.riding.direction.straight"
        }

        "quickbar_page" -> {
            val reverse = args["previous"]?.jsonPrimitive?.boolean ?: false
            control = if (reverse) "previous-active-quick-bar" else "next-active-quick-bar"
            setup = "local before=p.get_active_quick_bar_page(1)"
            confirmed = "p.get_active_quick_bar_page(1)~=before"
        }

        "use_item" -> {
            require(x != null) { "Target coordinates are required" }
            control = "use-item"
            setup =
                "assert(p.cursor_stack.valid_for_read and p.cursor_stack.prototype.type=='capsule', 'hold a usable capsule first')"
            confirmed = "false"
        }

        "clear_cursor" -> {
            control = "clear-cursor"
            setup = "if not p.cursor_stack.valid_for_read and not p.cursor_ghost then return {unchanged=true} end"
            confirmed = "not p.cursor_stack.valid_for_read and not p.cursor_ghost"
        }

        "quickbar" -> {
            val slot = args.getValue("slot").jsonPrimitive.int
            require(slot in 1..10) { "slot must be 1..10 on the first active quickbar" }
            control = "quick-bar-button-$slot"
            setup = """
                local item=assert(p.get_quick_bar_slot((p.get_active_quick_bar_page(1)-1)*10+$slot), 'quickbar slot is empty')
                local expected=item.name
                assert(not p.cursor_stack.valid_for_read or p.cursor_stack.name~=expected, 'item is already held')
            """.trimIndent()
            confirmed =
                "p.cursor_stack.valid_for_read and p.cursor_stack.name==expected or p.cursor_ghost and p.cursor_ghost.name.name==expected"
        }

        "build" -> {
            require(x != null) { "Build coordinates are required" }
            val ghost = args["ghost"]?.jsonPrimitive?.boolean ?: false
            control = if (ghost) "build-ghost" else "build"
            setup = """
                local held=p.cursor_stack
                local prototype=held.valid_for_read and held.prototype or p.cursor_ghost and p.cursor_ghost.name
                local entity=prototype and (prototype.place_result or prototype.plant_result)
                assert(entity, 'hold a placeable entity item first')
                local expected=entity.name
                local before=held.valid_for_read and held.count or 0
                local ghosts=p.surface.count_entities_filtered{position=position,name='entity-ghost',ghost_name=expected}
                local existing=p.surface.count_entities_filtered{position=position,name=expected}
                local ghostBuild=$ghost or p.cursor_ghost~=nil or p.controller_type==defines.controllers.remote
                assert(ghostBuild or p.can_build_from_cursor{position=position}, 'cannot build here with the current cursor')
            """.trimIndent()
            confirmed =
                "p.surface.count_entities_filtered{position=position,name='entity-ghost',ghost_name=expected}>ghosts or p.surface.count_entities_filtered{position=position,name=expected}>existing and (ghostBuild or not p.cursor_stack.valid_for_read or p.cursor_stack.count<before)"
        }

        "place_blueprint" -> {
            require(x != null) { "Blueprint coordinates are required" }
            control = "build-ghost"
            setup = """
                local held=p.cursor_stack
                assert(held.valid_for_read and held.is_blueprint and held.is_blueprint_setup(), 'hold a configured blueprint first')
                assert(#(held.get_blueprint_entities() or {})+#(held.get_blueprint_tiles() or {})>0, 'blueprint is empty')
            """.trimIndent()
            confirmed = "false"
        }

        "pave" -> {
            require(x != null) { "Paving coordinates are required" }
            control = "build"
            setup = """
                local held=p.cursor_stack
                local prototype=held.valid_for_read and held.prototype or p.cursor_ghost and p.cursor_ghost.name
                local tile=prototype and prototype.place_as_tile_result
                assert(tile, 'held item cannot place tiles')
                local expected=tile.result.name
                assert(p.surface.get_tile(position).name~=expected, 'target already has this tile')
                local before=held.valid_for_read and held.count or 0
                local ghostBuild=p.cursor_ghost~=nil or p.controller_type==defines.controllers.remote
                local function pendingTiles()
                    local count=0
                    for _,entity in pairs(p.surface.find_entities_filtered{position=position,name='tile-ghost'}) do
                        if entity.ghost_name==expected then count=count+1 end
                    end
                    return count
                end
                local ghosts=pendingTiles()
                assert(ghostBuild or p.can_build_from_cursor{position=position}, 'cannot pave here with the current cursor')
            """.trimIndent()
            confirmed =
                "pendingTiles()>ghosts or p.surface.get_tile(position).name==expected and (ghostBuild or not p.cursor_stack.valid_for_read or p.cursor_stack.count<before)"
        }

        "rotate" -> {
            require(x != null) { "Entity coordinates are required" }
            control = if (args["reverse"]?.jsonPrimitive?.boolean == true) "reverse-rotate" else "rotate"
            setup = """
                local entity
                for _,candidate in pairs(p.surface.find_entities_filtered{position=position}) do if not entity or candidate.type~='resource' then entity=candidate end end
                assert(entity, 'entity not found')
                position=entity.position
                local target=entity
                assert(not p.cursor_stack.valid_for_read and not p.cursor_ghost, 'clear the cursor first')
                assert(entity.rotatable and p.can_reach_entity(entity), 'entity is not rotatable or out of reach')
                local before=entity.direction
            """.trimIndent()
            confirmed = "entity.valid and entity.direction~=before"
        }

        "pipette" -> {
            require(x != null) { "Entity coordinates are required" }
            control = "pipette"
            setup = """
                local entity
                for _,candidate in pairs(p.surface.find_entities_filtered{position=position}) do if not entity or candidate.type~='resource' then entity=candidate end end
                assert(entity, 'entity not found')
                position=entity.position
                local target=entity
                local items=entity.prototype.items_to_place_this
                assert(items and items[1], 'entity has no placement item')
                local expected=items[1].name
                if p.cursor_stack.valid_for_read and p.cursor_stack.name==expected or p.cursor_ghost and p.cursor_ghost.name.name==expected then return {unchanged=true} end
            """.trimIndent()
            confirmed =
                "p.cursor_stack.valid_for_read and p.cursor_stack.name==expected or p.cursor_ghost and p.cursor_ghost.name.name==expected"
        }

        "drop_item" -> {
            require(x != null) { "Drop coordinates are required" }
            control = "drop-cursor"
            setup = """
                assert(p.cursor_stack.valid_for_read, 'cursor is empty')
                local before=p.cursor_stack.count
                assert(#p.surface.find_entities_filtered{position=position}==0, 'drop target must be empty ground')
                local requireEmptySelection=true
            """.trimIndent()
            confirmed = "not p.cursor_stack.valid_for_read or p.cursor_stack.count<before"
        }

        "transfer" -> {
            require(x != null) { "Entity coordinates are required" }
            control = if (args["half"]?.jsonPrimitive?.boolean == true) "fast-entity-split" else "fast-entity-transfer"
            setup = """
                local target
                for _,entity in pairs(p.surface.find_entities_filtered{position=position}) do if entity.type~='resource' and entity~=p.character then target=entity end end
                assert(target and p.can_reach_entity(target), 'no reachable target entity')
                position=target.position
            """.trimIndent()
            confirmed = "false"
        }

        "open_entity" -> {
            require(x != null) { "Entity coordinates are required" }
            control = "open-gui"
            setup = """
                assert(not p.cursor_stack.valid_for_read and not p.cursor_ghost, 'clear the cursor first')
                local target
                for _,entity in pairs(p.surface.find_entities_filtered{position=position}) do if entity.type~='resource' and entity~=p.character then target=entity end end
                assert(target and p.can_reach_entity(target), 'no reachable target entity')
                position=target.position
            """.trimIndent()
            confirmed = "p.opened==target"
        }

        "copy_settings", "paste_settings" -> {
            require(x != null) { "Entity coordinates are required" }
            control = if (kind == "copy_settings") "copy-entity-settings" else "paste-entity-settings"
            setup = """
                local target
                for _,entity in pairs(p.surface.find_entities_filtered{position=position}) do if entity.type~='resource' and entity~=p.character then target=entity end end
                assert(target and p.can_reach_entity(target), 'no reachable target entity')
                position=target.position
            """.trimIndent()
            confirmed = if (kind == "copy_settings") "p.entity_copy_source==target" else "false"
        }

        "vehicle" -> {
            control = "toggle-driving"
            setup = "local before=p.driving"
            confirmed = "p.driving~=before"
        }

        "switch_weapon" -> {
            control = "next-weapon"
            setup = """
                assert(not p.driving, 'weapon selection requires a character outside a vehicle')
                local before=p.character.selected_gun_index
                local guns=p.get_inventory(defines.inventory.character_guns)
                local occupied=0
                for i=1,#guns do if guns[i].valid_for_read then occupied=occupied+1 end end
                assert(occupied>1, 'equip at least two weapons first')
            """.trimIndent()
            confirmed = "p.character.selected_gun_index~=before"
        }

        else -> error("Unknown player operation: $kind")
    }
    val event = when (kind) {
        "use_item" -> "on_player_used_capsule"
        "transfer" -> "on_player_fast_transferred"
        "drop_item" -> "on_player_dropped_item"
        "paste_settings" -> "on_entity_settings_pasted"
        "place_blueprint" -> "on_built_entity"
        else -> null
    }
    return submitBasic(control, position, setup, confirmed, kind == "move", event, timeoutMillis)
}

private suspend fun GameProcess.submitBasic(
    control: String, position: String, setup: String, confirmed: String,
    movement: Boolean, event: String?, timeoutMillis: Int
): String {
    // Kept as a single closure so operation-specific predicates retain their validated objects.
    val task = """
        local bridge=assert(__factorio_mcp_resident_v1, 'world bridge is unavailable')
        local complete=bridge.completion('player_action')
        return {responseType='player_action',ready=function() return not bridge.player_action and not bridge.research end,callback=function()
            local p=assert(__factorio_mcp_resident_v1).player()
            assert(p.connected and p.character, 'a connected character is required')
            local surface,character=p.surface,p.character
            local position=$position
            local function run()
                $setup
                local deadline=bridge.native('now')+$timeoutMillis
                local finished,submitted,observed,pointed=false,false,false,false
                local removeEvent,removeControl
                local activated=false
                local closedAt,pointTick
                local function finish(value,failed)
                    if finished then return end
                    finished=true
                    bridge.native('pointer_end')
                    if removeEvent then removeEvent() end
                    if removeControl then removeControl() end
                    bridge.player_action=nil
                    complete(value,failed)
                end
                local function fail(reason) finish(reason,true) end
                local function validate()
                    assert(p.valid and p.connected and p.surface==surface and p.character==character, 'player, character or surface changed')
                    assert(bridge.native('now')<deadline, 'operation confirmation timed out; inspect game state before retrying')
                end
                ${
        if (event == null) "" else """
                removeEvent=bridge.event(defines.events.$event,function(e)
                    if submitted and e.player_index==p.index then observed=true;return true end
                    return false
                end,fail)
                """.trimIndent()
    }
                ${
        if (control != "drop-cursor") "" else """
                removeControl=bridge.observe('control',function()
                    if (activated or submitted) and bridge.native('control_name')=='drop-cursor' then
                        assert(bridge.native('control_value',activated and 1 or 0)=='applied', 'cannot submit drop control')
                        submitted=true
                    end
                    return false
                end,fail)
                """.trimIndent()
    }
                bridge.player_action=true
                bridge.observe('input',function()
                    if finished then return true end
                    validate()
                    if submitted then activated=false end
                    if not submitted and p.opened_gui_type~=defines.gui_type.none then
                        if closedAt and game.tick<=closedAt then return false end
                        closedAt=game.tick
                        local response=bridge.native('close_gui')
                        if response=='waiting' then return false end
                        assert(response=='submitted',response)
                        return false
                    end
                    assert(bridge.native('aim','',position)=='submitted', 'cannot scope world position')
                    if not pointed or target and p.selected~=target or requireEmptySelection and p.selected then
                        local result=bridge.native('point','',position)
                        assert(result=='submitted',result)
                        pointed=true
                        pointTick=game.tick
                        return false
                    end
                    if pointTick and game.tick<=pointTick then return false end
                    if target and p.selected~=target then return false end
                    if requireEmptySelection and p.selected then return false end
                    if not submitted then
                        ${
        if (control == "drop-cursor") "activated=true" else """
                        local result=bridge.native(${
            luaQuote(
                if (control in setOf(
                        "build",
                        "build-ghost",
                        "rotate",
                        "reverse-rotate",
                        "next-active-quick-bar",
                        "previous-active-quick-bar"
                    )
                ) control else "tap"
            )
        },${luaQuote(control)},position)
                        if result=='waiting' then return false end
                        assert(result=='submitted',result)
                        submitted=true
                        """.trimIndent()
    }
                    end
                    return false
                end,fail)
                bridge.observe('tick',function()
                    if finished then return true end
                    validate()
                    if pointTick and game.tick<=pointTick then return false end
                    if target and p.selected~=target then return false end
                    if requireEmptySelection and p.selected then return false end
                    if not submitted then return false end
                    if $confirmed then observed=true end
                    if observed and (not $movement or not p.walking_state.walking) then
                        finish({tick=game.tick,position=p.position,walking=p.walking_state,driving=p.driving,riding=p.driving and p.riding_state or nil,quickbar_page=p.get_active_quick_bar_page(1),selected_gun_index=p.character.selected_gun_index,
                            cursor=p.cursor_stack.valid_for_read and {name=p.cursor_stack.name,count=p.cursor_stack.count} or nil},false)
                        return true
                    end
                    return false
                end,fail)
            end
            local immediate=run()
            if immediate then
                bridge.player_action=true
                bridge.observe('tick',function() bridge.player_action=nil;complete(immediate,false);return true end,
                    function(reason) bridge.player_action=nil;complete(reason,true) end)
            end
        end}
    """.trimIndent()
    return submitPlayerTask(task, timeoutMillis)
}

/** Allow the game execution deadline to return its cleanup result before the IPC wait expires. */
private const val COMPLETION_GRACE_MILLIS = 1000

internal suspend fun GameProcess.submitPlayerTask(task: String, timeoutMillis: Int): String =
    Json.parseToJsonElement(submitTask(task, timeoutMillis + COMPLETION_GRACE_MILLIS)).jsonObject.getValue("value")
        .toString()
