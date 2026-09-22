package com.hiczp.factorio.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import kotlin.time.TimeSource

/** Compose confirmed normal inventory actions; never retry an uncertain mutation. */
internal suspend fun GameProcess.transferItems(args: JsonObject): String {
    val position = queryPosition(args, true)
    val name = args.getValue("name").jsonPrimitive.content
    val quality = args["quality"]?.jsonPrimitive?.content ?: "normal"
    val count = args.getValue("count").jsonPrimitive.int
    val direction = args.getValue("direction").jsonPrimitive.content
    val entityInventory = args["inventory"]?.jsonPrimitive?.content ?: "chest"
    val timeout = args["timeout_ms"]?.jsonPrimitive?.int ?: 30000
    require(count in 1..100 && timeout in 1000..60000) { "count must be 1..100 and timeout_ms 1000..60000" }
    require(direction in setOf("deposit", "withdraw")) { "direction must be deposit or withdraw" }
    require(!entityInventory.startsWith("character_")) { "Specify an entity inventory" }
    val sourceName = if (direction == "deposit") "character_main" else entityInventory
    val destinationName = if (direction == "deposit") entityInventory else "character_main"
    val started = TimeSource.Monotonic.markNow()
    fun remaining(): Int = (timeout - started.elapsedNow().inWholeMilliseconds.toInt()).also {
        check(it > 0) { "Transfer deadline expired" }
    }

    val initialState = Json.parseToJsonElement(status(remaining())).jsonObject
    suspend fun sameWorld() {
        val state = Json.parseToJsonElement(status(remaining())).jsonObject
        check(
            state["ready"] == JsonPrimitive(true) && state["instance"] == initialState["instance"] &&
                    state["world_generation"] == initialState["world_generation"]
        ) { "World changed during transfer" }
    }

    suspend fun snapshot(): JsonObject {
        sameWorld()
        return executeOnTick(
            """
            local p=assert(__factorio_mcp_resident_v1).player()
            local item={name=${luaQuote(name)},quality=${luaQuote(quality)}}
            local prototype=assert(prototypes.item[item.name], 'item not found')
            assert(prototypes.quality[item.quality], 'quality not found')
            local inventoryId=assert(defines.inventory[${luaQuote(entityInventory)}], 'unknown inventory')
            local entity
            for _,e in pairs(p.surface.find_entities_filtered{position=$position,force=p.force}) do
                if e.type~='character' and e.get_inventory(inventoryId) then entity=e;break end
            end
            assert(entity and entity.unit_number and p.can_reach_entity(entity), 'reachable friendly inventory entity not found')
            local characterInventory=assert(p.get_main_inventory(), 'character inventory unavailable')
            local entityInventory=assert(entity.get_inventory(defines.inventory[${luaQuote(entityInventory)}]), 'entity inventory unavailable')
            local source=${if (direction == "deposit") "characterInventory" else "entityInventory"}
            local destination=${if (direction == "deposit") "entityInventory" else "characterInventory"}
            local sourceSlot,destinationSlot
            local last=destination.supports_bar() and destination.get_bar()-1 or #destination
            for i=1,#source do
                local stack=source[i]
                if stack.valid_for_read and stack.name==item.name and stack.quality.name==item.quality then sourceSlot=i;break end
            end
            for i=1,last do
                local stack=destination[i]
                if not (destination.supports_filters() and destination.get_filter(i)) and
                    (not stack.valid_for_read or stack.name==item.name and stack.quality.name==item.quality and stack.count<prototype.stack_size) then
                    destinationSlot=i;break
                end
            end
            local cursor=p.cursor_stack
            return {tick=game.tick,player=p.index,force=p.force.name,surface=p.surface.name,entity=entity.unit_number,
                source_slot=sourceSlot,destination_slot=destinationSlot,source_count=source.get_item_count(item),
                cursor_count=cursor.valid_for_read and cursor.count or 0,cursor_name=cursor.valid_for_read and cursor.name or nil,
                cursor_quality=cursor.valid_for_read and cursor.quality.name or nil,cursor_ghost=p.cursor_ghost~=nil}
        """.trimIndent(), remaining()
        ).jsonObject
    }

    val initial = snapshot()
    val entityId = initial.getValue("entity").jsonPrimitive.long
    check(initial.getValue("cursor_count").jsonPrimitive.int == 0 && !initial.getValue("cursor_ghost").jsonPrimitive.boolean) { "Clear the cursor before transferring" }
    val positionArgs = buildJsonObject {
        put("x", args.getValue("x"))
        put("y", args.getValue("y"))
    }

    fun slotArgs(inventory: String, slot: JsonElement, action: String): JsonObject = buildJsonObject {
        put("inventory", inventory)
        put("slot", slot)
        put("action", action)
    }

    var transferred = 0
    var tookItems = false
    var returnSlot: JsonElement? = null
    var uncertain = false
    var reason: String? = null
    var cleanupError: String? = null
    try {
        basicAction("open_entity", positionArgs, remaining())
        while (transferred < count) {
            var current = snapshot()
            check(
                listOf(
                    "player",
                    "force",
                    "surface",
                    "entity"
                ).all { current[it] == initial[it] }) { "Transfer context changed" }
            if (current["destination_slot"] == null) {
                reason = "No unfiltered destination slot with capacity"
                break
            }
            if (current.getValue("cursor_count").jsonPrimitive.int == 0) {
                val sourceSlot = current["source_slot"]
                if (sourceSlot == null) {
                    reason = "Source inventory exhausted"
                    break
                }
                uncertain = true
                tookItems = true
                returnSlot = sourceSlot
                inventorySlot(slotArgs(sourceName, sourceSlot, "take"), remaining(), entityId)
                uncertain = false
                current = snapshot()
                check(
                    listOf(
                        "player",
                        "force",
                        "surface",
                        "entity"
                    ).all { current[it] == initial[it] }) { "Transfer context changed" }
            }
            check(current["cursor_name"]?.jsonPrimitive?.content == name && current["cursor_quality"]?.jsonPrimitive?.content == quality) { "Cursor contents changed" }
            val destinationSlot = current["destination_slot"] ?: error("Destination capacity changed")
            val held = current.getValue("cursor_count").jsonPrimitive.int
            uncertain = true
            val result = Json.parseToJsonElement(
                inventorySlot(
                    slotArgs(destinationName, destinationSlot, "put_one"),
                    remaining(),
                    entityId
                )
            ).jsonObject
            check(result.getValue("cursor").jsonObject.getValue("count").jsonPrimitive.int == held - 1) { "Unexpected transfer amount; inspect inventories" }
            transferred++
            uncertain = false
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        reason = e.message ?: "Transfer failed"
    }
    if (tookItems && !uncertain) {
        try {
            val current = snapshot()
            check(
                listOf(
                    "player",
                    "force",
                    "surface",
                    "entity"
                ).all { current[it] == initial[it] }) { "Transfer context changed before returning leftovers" }
            if (current.getValue("cursor_count").jsonPrimitive.int > 0) {
                check(current["cursor_name"]?.jsonPrimitive?.content == name && current["cursor_quality"]?.jsonPrimitive?.content == quality) { "Cursor contents changed before returning leftovers" }
                uncertain = true
                val result = Json.parseToJsonElement(
                    inventorySlot(
                        slotArgs(sourceName, checkNotNull(returnSlot), "put"),
                        remaining(),
                        entityId
                    )
                ).jsonObject
                check(result.getValue("cursor").jsonObject.getValue("count").jsonPrimitive.int == 0) { "Source slot cannot accept all leftovers; inspect cursor" }
                uncertain = false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            cleanupError = e.message ?: "Cursor cleanup failed"
        }
    }
    return buildJsonObject {
        put("name", name)
        put("quality", quality)
        put("direction", direction)
        put("inventory", entityInventory)
        put("requested", count)
        put("transferred", transferred)
        put("complete", transferred == count && !uncertain && cleanupError == null)
        put("uncertain", uncertain)
        put("cursor_may_hold_items", tookItems && (uncertain || cleanupError != null))
        reason?.let { put("reason", it) }
        cleanupError?.let { put("cleanup_error", it) }
        put("elapsed_ms", started.elapsedNow().inWholeMilliseconds)
    }.toString()
}
