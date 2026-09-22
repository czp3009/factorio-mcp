package com.hiczp.factorio.mcp

import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import kotlin.time.TimeSource

/** Passive waits live in MCP and leave no task or held input behind when cancelled. */
internal suspend fun GameProcess.waitFor(args: JsonObject): String {
    val kind = args.getValue("kind").jsonPrimitive.content
    val timeout = args["timeout_ms"]?.jsonPrimitive?.int ?: 10000
    require(timeout in 1..60000) { "timeout_ms must be 1..60000" }
    val expression = when (kind) {
        "inventory_count", "entity_inventory" -> {
            val name = args.getValue("name").jsonPrimitive.content
            val quality = args["quality"]?.jsonPrimitive?.content ?: "normal"
            val count = args.getValue("count").jsonPrimitive.int
            require(count >= 0) { "count must be nonnegative" }
            """
                assert(prototypes.item[${luaQuote(name)}] and prototypes.quality[${luaQuote(quality)}], 'unknown item or quality')
                ${
                if (kind == "entity_inventory") entityAtPositionSource(
                    queryPosition(
                        args,
                        true
                    )
                ) else "local entity=p"
            }
                local inventoryId=assert(defines.inventory[${luaQuote(args["inventory"]?.jsonPrimitive?.content ?: if (kind == "entity_inventory") "chest" else "character_main")}], 'unknown inventory')
                local inventory=assert(entity.get_inventory(inventoryId), 'inventory is unavailable')
                value=inventory.get_item_count{name=${luaQuote(name)},quality=${luaQuote(quality)}}
                matched=value>=$count
            """
        }

        "position" -> {
            val tolerance = args["tolerance"]?.jsonPrimitive?.double ?: 0.5
            require(tolerance.isFinite() && tolerance in 0.0..64.0) { "tolerance must be 0..64" }
            """
                local target=${queryPosition(args, true)}
                local dx,dy=p.position.x-target.x,p.position.y-target.y
                value=math.sqrt(dx*dx+dy*dy);matched=value<=$tolerance
            """
        }

        "crafting_empty" -> "value=#(p.crafting_queue or {});matched=value==0"
        "research_complete" -> """
            local technology=assert(p.force.technologies[${luaQuote(args.getValue("name").jsonPrimitive.content)}], 'technology not found')
            value=technology.researched;matched=value
        """

        "entity_status" -> """
            local wanted=assert(defines.entity_status[${luaQuote(args.getValue("status").jsonPrimitive.content)}], 'unknown entity status')
            local entities=p.surface.find_entities_filtered{position=${
            queryPosition(
                args,
                true
            )
        },name=${luaQuote(args.getValue("name").jsonPrimitive.content)},force=p.force,limit=1}
            local entity=entities[1]
            value=entity and entity.status or false;matched=entity~=nil and value==wanted
        """

        else -> error("kind must be inventory_count, entity_inventory, position, crafting_empty, research_complete or entity_status")
    }
    val started = TimeSource.Monotonic.markNow()
    val initial = Json.parseToJsonElement(status(timeout)).jsonObject
    check(initial["ready"]?.jsonPrimitive?.boolean == true) { "The world bridge is not ready" }
    suspend fun verifyWorld(remaining: Int) {
        val state = Json.parseToJsonElement(status(remaining)).jsonObject
        check(
            state["ready"] == JsonPrimitive(true) && state["instance"] == initial["instance"] &&
                    state["world_generation"] == initial["world_generation"]
        ) { "World changed while waiting" }
    }

    var last: JsonObject? = null
    var firstObservation: JsonObject? = null
    while (true) {
        val remaining = timeout - started.elapsedNow().inWholeMilliseconds.toInt()
        if (remaining <= 0) break
        verifyWorld(remaining)
        last = executeOnTick(
            """
            local p=assert(__factorio_mcp_resident_v1).player()
            local value,matched
            ${expression.trimIndent()}
            return {tick=game.tick,player=p.index,force=p.force.name,surface=p.surface.name,matched=matched,value=value}
        """.trimIndent(), remaining
        ).jsonObject
        if (firstObservation == null) firstObservation = last
        check(
            listOf(
                "player",
                "force",
                "surface"
            ).all { last[it] == firstObservation[it] }) { "Player context changed while waiting" }
        if (last.getValue("matched").jsonPrimitive.boolean) {
            verifyWorld((timeout - started.elapsedNow().inWholeMilliseconds.toInt()).coerceAtLeast(1))
            return waitResult(kind, started.elapsedNow().inWholeMilliseconds, last)
        }
        delay(minOf(250L, (timeout - started.elapsedNow().inWholeMilliseconds).coerceAtLeast(0)))
    }
    return waitResult(kind, started.elapsedNow().inWholeMilliseconds, last)
}

private fun waitResult(kind: String, elapsedMillis: Long, observation: JsonObject?): String = buildJsonObject {
    put("kind", kind)
    put("matched", observation?.get("matched") ?: JsonPrimitive(false))
    put("elapsed_ms", elapsedMillis)
    put("observation", observation ?: JsonNull)
}.toString()
