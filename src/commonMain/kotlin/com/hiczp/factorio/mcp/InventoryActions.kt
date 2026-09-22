package com.hiczp.factorio.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

internal suspend fun GameProcess.inventoryItem(
    name: String,
    quality: String,
    equip: Boolean,
    timeoutMillis: Int
): String {
    require(name.isNotBlank() && quality.isNotBlank()) { "Item name and quality are required" }
    val setup = """
        local inventoryName='character_main'
        local inventory=p.get_main_inventory()
        local item={name=${luaQuote(name)},quality=${luaQuote(quality)}}
        assert(not p.cursor_stack.valid_for_read and not p.cursor_ghost, 'clear the cursor first')
        local stack=assert(inventory.find_item_stack(item), 'item is not in the main inventory')
        local before=stack.count
        local slot
        for i=1,#inventory do if inventory[i]==stack then slot=i;break end end
        ${
        if (!equip) "" else """
        local prototype=assert(prototypes.item[item.name], 'unknown item')
        local inventories={armor='character_armor',gun='character_guns',ammo='character_ammo'}
        local destinationName=assert(inventories[prototype.type], 'only armor, guns and ammunition can be equipped')
        local destination=assert(p.get_inventory(defines.inventory[destinationName]))
        local equippedBefore=destination.get_item_count(item)
        """.trimIndent()
    }
    """.trimIndent()
    val confirmed = if (equip) "destination.get_item_count(item)>equippedBefore" else
        "p.cursor_stack.valid_for_read and p.cursor_stack.name==item.name and p.cursor_stack.quality.name==item.quality"
    return inventoryTask(setup, confirmed, if (equip) "slot_transfer" else "slot_pick", true, timeoutMillis)
}

internal suspend fun GameProcess.inventorySlot(
    args: JsonObject,
    timeoutMillis: Int,
    expectedEntity: Long? = null
): String {
    val inventory = args["inventory"]?.jsonPrimitive?.content ?: "character_main"
    val slot = args.getValue("slot").jsonPrimitive.int
    require(slot > 0) { "slot must be a positive one-based index" }
    val action = args.getValue("action").jsonPrimitive.content
    val precondition: String
    val confirmed: String
    when (action) {
        "take", "take_half" -> {
            precondition =
                "assert(not cursor.valid_for_read and not p.cursor_ghost and stack.valid_for_read, 'take requires an empty cursor and an occupied slot')"
            confirmed =
                "cursor.valid_for_read and cursor.name==source.name and cursor.quality.name==source.quality and inventory.get_item_count(source)<sourceCount"
        }

        "put", "put_one" -> {
            precondition =
                "assert(cursor.valid_for_read, 'hold an item first'); assert(not stack.valid_for_read or stack.name==cursor.name and stack.quality.name==cursor.quality.name, 'destination contains another item; use swap explicitly')"
            confirmed = "snapshot(cursor).count<held.count and inventory.get_item_count(held)>heldCount"
        }

        "swap" -> {
            precondition =
                "assert(cursor.valid_for_read and stack.valid_for_read, 'swap requires an occupied cursor and slot'); assert(cursor.name~=stack.name or cursor.quality.name~=stack.quality.name, 'same item stacks would merge; use put')"
            confirmed =
                "cursor.valid_for_read and cursor.name==source.name and cursor.quality.name==source.quality and inventory.get_item_count(held)>heldCount"
        }

        "transfer" -> {
            precondition =
                "assert(not cursor.valid_for_read and not p.cursor_ghost and stack.valid_for_read, 'transfer requires an empty cursor and an occupied slot')"
            confirmed = "inventory.get_item_count(source)<sourceCount"
        }

        "send_to_planet" -> {
            require(inventory == "hub_main") { "send_to_planet requires inventory=hub_main" }
            precondition = """
                assert(entity.type=='space-platform-hub' and entity.force==p.force, 'open an owned platform hub')
                local orbit=entity.surface.platform.space_location
                assert(orbit and game.planets[orbit.name], 'the platform must orbit a planet')
                assert(not cursor.valid_for_read and not p.cursor_ghost and stack.valid_for_read, 'send_to_planet requires an empty cursor and an occupied slot')
            """.trimIndent()
            confirmed = "inventory.get_item_count(source)<sourceCount"
        }

        "set_filter" -> {
            precondition = """
                assert(inventory.supports_filters(), 'inventory does not support filters')
                assert(not inventory.get_filter(slot), 'clear the existing filter first')
                local filterSource=cursor.valid_for_read and cursor or stack
                assert(filterSource.valid_for_read, 'hold an item or select an occupied slot')
                local expectedFilter=filterSource.name
            """.trimIndent()
            confirmed = "inventory.get_filter(slot) and inventory.get_filter(slot).name==expectedFilter"
        }

        "clear_filter" -> {
            precondition =
                "assert(inventory.supports_filters() and inventory.get_filter(slot), 'slot has no filter'); assert(not cursor.valid_for_read, 'clear the cursor first')"
            confirmed = "not inventory.get_filter(slot)"
        }

        "open" -> {
            precondition = "assert(stack.valid_for_read, 'slot is empty')"
            confirmed =
                "p.opened and (p.opened.object_name=='LuaItemStack' and p.opened==stack or p.opened.object_name=='LuaEquipmentGrid' and stack.grid and p.opened.unique_id==stack.grid.unique_id)"
        }

        else -> error("action must be take, take_half, put, put_one, swap, transfer, send_to_planet, set_filter, clear_filter or open")
    }
    val characterInventory = inventory.startsWith("character_")
    val setup = """
        local inventoryName=${luaQuote(inventory)}
        local inventoryId=assert(defines.inventory[inventoryName], 'unknown inventory name')
        ${
        if (characterInventory) "local inventory=assert(p.get_inventory(inventoryId), 'inventory is unavailable')" else """
        local entity=p.opened
        assert(entity and entity.object_name=='LuaEntity' and p.can_reach_entity(entity), 'open a reachable entity first')
        local inventory=assert(entity.get_inventory(inventoryId), 'entity inventory is unavailable')
        """.trimIndent()
    }
        local slot=$slot
        assert(slot<=#inventory, 'slot is outside the inventory')
        local stack,cursor=inventory[slot],p.cursor_stack
        $precondition
        local source,held=snapshot(stack),snapshot(cursor)
        local sourceCount=source.name and inventory.get_item_count(source) or 0
        local heldCount=held.name and inventory.get_item_count(held) or 0
    """.trimIndent()
    val control = when (action) {
        "take_half", "put_one" -> "slot_split"
        "transfer" -> "slot_transfer"
        "send_to_planet" -> "slot_send"
        "open" -> "slot_open"
        "set_filter", "clear_filter" -> "slot_filter"
        else -> "slot_pick"
    }
    return inventoryTask(setup, confirmed, control, characterInventory, timeoutMillis, expectedEntity)
}

private suspend fun GameProcess.inventoryTask(
    setup: String, confirmed: String, control: String,
    characterInventory: Boolean, timeoutMillis: Int, expectedEntity: Long? = null
): String {
    val openCharacterGui = characterInventory && expectedEntity == null
    val task = """
        local b=assert(__factorio_mcp_resident_v1)
        local complete=b.completion('inventory')
        return {responseType='inventory',ready=function() return not b.player_action and not b.research end,callback=function()
            local p=assert(__factorio_mcp_resident_v1).player()
            assert(p.character, 'a character is required')
            local character,surface=p.character,p.surface
            local function snapshot(stack)
                if not stack.valid_for_read then return {count=0} end
                return {name=stack.name,quality=stack.quality.name,count=stack.count}
            end
            $setup
            ${if (expectedEntity != null && characterInventory) "local entity=p.opened;assert(entity and entity.object_name=='LuaEntity' and p.can_reach_entity(entity), 'keep the target entity open')" else ""}
            ${if (expectedEntity != null) "assert(entity.unit_number==$expectedEntity, 'opened transfer target changed')" else ""}
            local deadline=b.native('now')+$timeoutMillis
            local finished=false
            local stage=${if (openCharacterGui) 0 else 2}
            local phaseTick
            local function finish(value,failed)
                if finished then return end
                finished=true;b.native('pointer_end');b.player_action=nil;complete(value,failed)
            end
            local function fail(reason) finish(reason,true) end
            local function validate()
                assert(p.valid and p.connected and p.character==character and p.surface==surface, 'player, character or surface changed')
                assert(inventory.valid, 'inventory became unavailable')
                ${if (openCharacterGui) "" else "assert(entity.valid and (stage==4 or p.opened==entity) and p.can_reach_entity(entity), 'opened entity changed or became unavailable')"}
                assert(b.native('now')<deadline, string.format('inventory operation timed out in stage %d; inspect inventory before retrying',stage))
            end
            b.player_action=true
            b.observe('input',function()
                if finished then return true end
                validate()
                if stage==0 then
                    if p.opened_gui_type~=defines.gui_type.none then
                        if phaseTick and game.tick<=phaseTick then return false end
                        phaseTick=game.tick
                        local result=b.native('close_gui')
                        if result=='waiting' then return false end
                        assert(result=='submitted',result)
                        return false
                    end
                    stage=1
                elseif stage==1 and p.opened_gui_type==defines.gui_type.none then
                    assert(b.native('tap','open-character-gui',p.position)=='submitted', 'cannot open inventory')
                    stage=2
                elseif stage==2 ${if (openCharacterGui) "and p.opened_gui_type==defines.gui_type.controller" else ""} then
                    local result=b.native('slot_point',stack)
                    if result=='waiting' then return false end
                    assert(result=='submitted',result)
                    phaseTick=game.tick
                    stage=3
                elseif stage==3 and game.tick>phaseTick then
                    local result=b.native(${luaQuote(control)},stack)
                    assert(result=='submitted',result)
                    stage=4
                end
                return false
            end,fail)
            b.observe('tick',function()
                if finished then return true end
                validate()
                if stage==4 and $confirmed then
                    finish({inventory=inventoryName,slot=slot,stack=snapshot(stack),filter=inventory.supports_filters() and inventory.get_filter(slot) or nil,cursor=snapshot(p.cursor_stack),tick=game.tick},false)
                    return true
                end
                return false
            end,fail)
        end}
    """.trimIndent()
    return submitPlayerTask(task, timeoutMillis)
}

internal suspend fun GameProcess.cancelCrafting(index: Int, count: Int, timeoutMillis: Int): String {
    require(index in 1..65535 && count in 1..100) { "Invalid queue index or count (1..100)" }
    val task = """
        local b=assert(__factorio_mcp_resident_v1)
        local complete=b.completion('cancel_crafting')
        return {responseType='cancel_crafting',ready=function() return not b.player_action and not b.research end,callback=function()
            local p=assert(__factorio_mcp_resident_v1).player()
            local entry=assert((p.crafting_queue or {})[$index], 'crafting queue entry not found')
            local name=entry.recipe
            assert(entry.count>=$count, 'requested cancellation exceeds the queue entry')
            local deadline=b.native('now')+$timeoutMillis
            local finished,pointed=false,false
            local submitted,cancelled=0,0
            local removeEvent
            b.player_action=true
            local function finish(value,failed)
                if finished then return end
                finished=true;b.native('pointer_end');removeEvent();b.player_action=nil;complete(value,failed)
            end
            local function fail(reason) finish(reason,true) end
            removeEvent=b.event(defines.events.on_player_cancelled_crafting,function(event)
                if event.player_index==p.index and event.recipe.name==name then
                    cancelled=cancelled+event.cancel_count
                    pointed=false
                    if cancelled>=$count then finish({recipe=name,cancelled=cancelled,tick=event.tick},false);return true end
                end
                return false
            end,fail)
            b.observe('input',function()
                if finished then return true end
                assert(p.valid and p.connected and p.character, 'player became unavailable')
                assert(b.native('now')<deadline, 'cancellation timed out; inspect crafting queue before retrying')
                if submitted>cancelled then return false end
                local current=(p.crafting_queue or {})[$index]
                assert(current and current.recipe==name, 'crafting entry changed before cancellation completed')
                if not pointed then
                    local result=b.native('cancel_point',$index)
                    if result=='waiting' then return false end
                    assert(result=='submitted',result);pointed=true
                else
                    local result=b.native('cancel_craft',$index)
                    assert(result=='submitted',result);submitted=submitted+1
                end
                return false
            end,fail)
        end}
    """.trimIndent()
    return submitPlayerTask(task, timeoutMillis)
}
