package com.hiczp.factorio.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** The server's action mutex keeps the clipboard source and target submission together. */
internal suspend fun GameProcess.copyEntitySettings(args: JsonObject, timeoutMillis: Int): String {
    val source = args.getValue("source").jsonObject
    val target = args.getValue("target").jsonObject
    for (position in listOf(source, target)) {
        require(position.keys == setOf("x", "y")) { "source and target require only x and y" }
        queryPosition(position, true)
    }
    val initial = Json.parseToJsonElement(status(timeoutMillis)).jsonObject
    basicAction("copy_settings", source, timeoutMillis)
    val current = Json.parseToJsonElement(status(timeoutMillis)).jsonObject
    check(
        initial["instance"] == current["instance"] && initial["world_generation"] == current["world_generation"] &&
                current["ready"] == JsonPrimitive(true)
    ) { "World changed after copying settings; no paste submitted" }
    return basicAction("paste_settings", target, timeoutMillis)
}
