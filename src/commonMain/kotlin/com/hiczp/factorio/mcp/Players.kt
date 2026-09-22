package com.hiczp.factorio.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/** Public summaries never select a character for another tool. */
internal suspend fun GameProcess.players(request: JsonObject, timeoutMillis: Int): String {
    val id = request["id"]?.jsonPrimitive?.int
    val name = request["name"]?.jsonPrimitive?.also { require(it.isString) }?.content
    val limit = request["limit"]?.jsonPrimitive?.int ?: 50
    val offset = request["offset"]?.jsonPrimitive?.int ?: 0
    require(id == null || id > 0) { "id must be a positive player index" }
    require(id == null || name == null) { "Specify either id or name" }
    require(limit in 1..128 && offset >= 0) { "Invalid query bounds" }
    val selector = id?.toString() ?: name?.let(::luaQuote)
    val candidates = if (selector == null) "local candidates=game.players" else """
        local selected=assert(game.get_player($selector), 'player not found')
        local candidates={selected}
    """.trimIndent()
    return executeOnTick(
        """
        $candidates
        local players,total={},0
        for _,p in pairs(candidates) do
            total=total+1
            if total>$offset and #players<$limit then
                players[#players+1]={id=p.index,name=p.name,connected=p.connected,
                    life_state=p.character and 'alive' or (p.ticks_to_respawn and 'respawning' or 'no_character'),
                    surface=p.physical_surface.name,position=p.physical_position}
            end
        end
        return {tick=game.tick,total=total,offset=$offset,players=players}
    """.trimIndent(), timeoutMillis
    ).toString()
}
