package com.hiczp.factorio.mcp

/** A scoped control override expires entirely inside the game's Lua environment. */
internal suspend fun GameProcess.finiteControl(kind: String, x: Double?, y: Double?, timeoutMillis: Int): String {
    require((x == null) == (y == null) && (x == null || x.isFinite() && y!!.isFinite())) { "Coordinates must be finite" }
    val control: String
    val event: String
    val setup: String
    val matches: String
    val result: String
    val stopped: String
    var observeResult = ""
    when (kind) {
        "mine" -> {
            require(x != null) { "Mining coordinates are required" }
            control = "mine"
            event = "on_player_mined_item"
            setup = """
                local target
                for _,entity in pairs(p.surface.find_entities_filtered{position={x=$x,y=$y}}) do
                    if (entity.minable or entity.type=='entity-ghost' or entity.type=='tile-ghost') and entity~=p.character then
                        if not target or entity.type~='resource' then target=entity end
                    end
                end
                local remote=p.controller_type==defines.controllers.remote
                assert(target and (remote or p.can_reach_entity(target)), 'no reachable minable entity at the target')
                assert(not target.to_be_deconstructed(), 'target is already marked for deconstruction')
                local position=target.position
                local name=target.name
                local resource=target.type=='resource'
            """.trimIndent()
            matches = "event.player_index==p.index"
            result = "{entity=name,position=position,item=event.item_stack,tick=event.tick}"
            stopped = "not p.mining_state.mining"
            observeResult = """
                if active and not resource then
                    if not target.valid or remote and target.to_be_deconstructed() then
                        result={entity=name,position=position,tick=game.tick,
                            action=target.valid and 'deconstruction_ordered' or 'removed'}
                        active=false
                    end
                end
            """.trimIndent()
        }

        "pickup" -> {
            control = "pick-items"
            event = "on_picked_up_item"
            setup =
                "local position=p.position; assert(p.surface.count_entities_filtered{position=position,radius=p.item_pickup_distance,type='item-entity'}>0, 'no ground items within pickup reach')"
            matches = "event.player_index==p.index"
            result = "{item=event.item_stack,tick=event.tick}"
            stopped = "not p.picking_state"
        }

        "repair" -> {
            require(x != null) { "Target coordinates are required" }
            control = "build"
            event = "on_player_repaired_entity"
            setup = """
                assert(p.cursor_stack.valid_for_read and p.cursor_stack.prototype.type=='repair-tool', 'hold a repair pack first')
                local target
                for _,entity in pairs(p.surface.find_entities_filtered{position={x=$x,y=$y}}) do
                    if entity~=p.character and entity.health and entity.health<entity.max_health then target=entity end
                end
                assert(target and p.can_reach_entity(target), 'no reachable damaged entity')
                local position=target.position
                local name=target.name
            """.trimIndent()
            matches = "event.player_index==p.index and event.entity==target"
            result = "{entity=name,position=position,health=target.health,tick=event.tick}"
            stopped = "not p.repair_state.repairing"
        }

        "attack" -> {
            require(x != null) { "Target coordinates are required" }
            control = "shoot-selected"
            event = "on_entity_damaged"
            setup = """
                local target
                for _,entity in pairs(p.surface.find_entities_filtered{position={x=$x,y=$y}}) do
                    if entity~=p.character and entity.destructible and entity.health then target=entity end
                end
                assert(target, 'no destructible target entity')
                local position=target.position
                local name=target.name
                local ammoInventory=p.character.get_inventory(defines.inventory.character_ammo)
                local gun=p.character.selected_gun_index
                local ammunition=ammoInventory[gun]
                assert(ammunition.valid_for_read, 'the selected weapon has no ammunition')
                local ammoName,ammoCount,ammoRemaining=ammunition.name,ammunition.count,ammunition.ammo
            """.trimIndent()
            matches = "event.entity==target and event.cause==p.character"
            result =
                "{entity=name,position=position,damage=event.final_damage_amount,health=event.final_health,tick=event.tick}"
            stopped = "p.shooting_state.state==defines.shooting.not_shooting"
            observeResult = """
                if active and (not ammunition.valid_for_read or ammunition.count<ammoCount or ammunition.ammo<ammoRemaining) then
                    result={entity=name,position=position,ammunition=ammoName,action='fired',tick=game.tick}
                    active=false
                end
            """.trimIndent()
        }

        else -> error("Unknown finite control")
    }
    val task = """
        local b=assert(__factorio_mcp_resident_v1)
        local complete=b.completion(${luaQuote(kind)})
        return {responseType=${luaQuote(kind)},ready=function() return not b.player_action and not b.research end,callback=function()
            local p=assert(__factorio_mcp_resident_v1).player()
            assert(p.character and not p.driving, 'operation requires a character outside a vehicle')
            local character,surface=p.character,p.surface
            $setup
            local deadline=b.native('now')+$timeoutMillis
            local finished,pointed,active,released=false,false,false,false
            local result,failure,closedAt
            local removeEvent,removeControl
            local function finish()
                if finished then return end
                finished=true
                b.native('pointer_end')
                if removeEvent then removeEvent() end
                if removeControl then removeControl() end
                b.player_action=nil
                complete(failure or result,failure~=nil)
            end
            local function fail(reason)
                active=false;failure=tostring(reason)
                -- An observer that throws is removed by the dispatcher. Cleanup must not
                -- depend on that observer running again on a later input phase.
                finish()
            end
            removeEvent=b.event(defines.events.$event,function(event)
                if active and $matches then
                    result=$result
                    active=false
                    return true
                end
                return false
            end,fail)
            removeControl=b.observe('control',function()
                if b.native('control_name')==${luaQuote(control)} and pointed then
                    assert(b.native('control_value',active and 1 or 0)=='applied', 'cannot apply finite control')
                    if not active then released=true end
                end
                return false
            end,fail)
            b.player_action=true
            b.observe('input',function()
                if finished then return true end
                if b.native('now')>=deadline then fail('operation timed out; input released; inspect target and inventory') end
                if not p.valid or not p.connected or p.character~=character or p.surface~=surface then fail('player, character or surface changed') end
                if failure then
                    -- No pressed event was stored: removing the scoped override restores normal input.
                    finish()
                    return true
                end
                if p.opened_gui_type~=defines.gui_type.none then
                    if closedAt and game.tick<=closedAt then return false end
                    closedAt=game.tick
                    local response=b.native('close_gui')
                    if response=='waiting' then return false end
                    assert(response=='submitted',response)
                    return false
                end
                assert(b.native('aim','',position)=='submitted', 'cannot scope world position')
                if not pointed or target and target.valid and p.selected~=target then
                    local response=b.native('point','',position)
                    assert(response=='submitted',response)
                    pointed=true
                elseif not result and (not target or target.valid and p.selected==target) then active=true end
                return false
            end,fail)
            b.observe('tick',function()
                if finished then return true end
                $observeResult
                if result and released and $stopped then finish();return true end
                return false
            end,fail)
        end}
    """.trimIndent()
    return submitPlayerTask(task, timeoutMillis)
}
