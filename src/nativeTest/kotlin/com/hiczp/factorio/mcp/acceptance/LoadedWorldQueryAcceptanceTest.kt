@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.acceptance

import com.hiczp.factorio.mcp.McpHttpClient
import com.hiczp.factorio.mcp.toolValue
import kotlinx.cinterop.toKString
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Requires a disposable loaded world with generated origin tiles and at least three entities near the origin.
 * This single-client check is separate from authoritative multiplayer/full-CRC acceptance. */
class LoadedWorldQueryAcceptanceTest {
    @Test
    fun readsBoundedValuesAndRecoversAfterNativeLuaError() = runBlocking {
        fun environment(name: String) = checkNotNull(getenv(name)) { "Select $name explicitly" }.toKString()
        val client = McpHttpClient(environment("FACTORIO_MCP_ACCEPTANCE_URL"))
        val pid = environment("FACTORIO_MCP_TEST_PID").toInt()
        var attached = false
        withTimeout(600_000) {
            try {
                client.initialize()
                client.tool("attach", buildJsonObject { put("pid", pid) }).toolValue()
                attached = true
                val playerRequest = buildJsonObject {
                    putJsonObject("selection") { put("kind", "player") }
                }
                val before = client.tool("world_query", playerRequest).toolValue()
                assertEquals("live_world", before.getValue("observation").jsonPrimitive.content)
                val player = before.getValue("player").jsonObject
                assertTrue(player.getValue("index").jsonPrimitive.int > 0)
                val inspection = client.tool("world_query", buildJsonObject {
                    putJsonObject("selection") {
                        put("kind", "inspect")
                        putJsonObject("target") { put("kind", "game") }
                    }
                    putJsonArray("fields") {
                        add("tick")
                        add("players")
                    }
                }).toolValue()
                assertEquals("object_inspection", inspection.getValue("observation").jsonPrimitive.content)
                val game = inspection.getValue("objects").jsonArray.single().jsonObject
                assertEquals("LuaGameScript", game.getValue("object_name").jsonPrimitive.content)
                val attributes = game.getValue("attributes").jsonObject
                assertEquals(inspection.getValue("tick"), attributes.getValue("tick"))
                assertEquals(
                    "LuaCustomTable", attributes.getValue("players").jsonObject
                        .getValue("object_name").jsonPrimitive.content
                )
                fun area(left: Double, top: Double, right: Double, bottom: Double) = buildJsonObject {
                    putJsonObject("left_top") {
                        put("x", left)
                        put("y", top)
                    }
                    putJsonObject("right_bottom") {
                        put("x", right)
                        put("y", bottom)
                    }
                }

                val entityRequest = buildJsonObject {
                    putJsonObject("selection") {
                        put("kind", "entities")
                        put("area", area(-32.0, -32.0, 64.0, 32.0))
                    }
                    putJsonArray("fields") {
                        add("name")
                        add("type")
                        add("quality")
                    }
                    put("limit", 2)
                }
                val entities = client.tool("world_query", entityRequest).toolValue()
                assertEquals(2, entities.getValue("objects").jsonArray.size)
                assertTrue(entities.getValue("truncated").jsonPrimitive.boolean)
                assertTrue(entities.getValue("candidates_observed").jsonPrimitive.int > 2)
                val first = entities.getValue("objects").jsonArray.first().jsonObject
                assertEquals("LuaEntity", first.getValue("object_name").jsonPrimitive.content)
                val name = first.getValue("attributes").jsonObject.getValue("name")
                val prototype = client.tool("world_query", buildJsonObject {
                    putJsonObject("selection") {
                        put("kind", "prototypes")
                        put("type", "entity")
                        putJsonArray("names") { add(name) }
                    }
                    putJsonArray("fields") { add("name") }
                }).toolValue().getValue("objects").jsonArray.single().jsonObject
                assertEquals("LuaEntityPrototype", prototype.getValue("object_name").jsonPrimitive.content)
                assertEquals(name, prototype.getValue("attributes").jsonObject.getValue("name"))
                val tiles = client.tool("world_query", buildJsonObject {
                    putJsonObject("selection") {
                        put("kind", "tiles")
                        put("area", area(-0.5, -0.5, 1.0, 1.0))
                    }
                    put("limit", 1)
                }).toolValue()
                assertEquals(1, tiles.getValue("objects").jsonArray.size)
                assertEquals(4, tiles.getValue("candidates_observed").jsonPrimitive.int)
                assertTrue(tiles.getValue("truncated").jsonPrimitive.boolean)
                val missing = client.tool("world_query", buildJsonObject {
                    putJsonObject("selection") {
                        put("kind", "tiles")
                        put("position", player.getValue("position"))
                    }
                    put("surface", "__mcp_absent_surface__")
                })
                assertTrue(missing["isError"]?.jsonPrimitive?.boolean == true)
                assertTrue(missing.getValue("content").jsonArray.any {
                    it.jsonObject["text"]?.jsonPrimitive?.content?.contains("Requested surface is unavailable") == true
                }, missing.toString())
                repeat(5) {
                    val after = client.tool("world_query", playerRequest).toolValue().getValue("player").jsonObject
                    assertEquals(player.getValue("index"), after.getValue("index"))
                    assertEquals(player.getValue("position"), after.getValue("position"))
                    assertTrue(client.tool("ui_read").toolValue().getValue("nodes").jsonArray.isNotEmpty())
                }
                assertFalse(client.tool("detach").toolValue().getValue("attached").jsonPrimitive.boolean)
                attached = false
                println("factorio-mcp loaded world: player, API inspection, bounded entities/tiles, prototype identity, Lua error recovery and detach passed")
            } finally {
                withContext(NonCancellable) {
                    try {
                        if (attached) client.tool("detach").toolValue()
                    } finally {
                        client.close()
                    }
                }
            }
        }
    }
}
