@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.acceptance

import com.hiczp.factorio.mcp.McpHttpClient
import com.hiczp.factorio.mcp.toolValue
import kotlinx.cinterop.toKString
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.*
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reads only the explicitly selected local acceptance world; no console or administrator access.
 */
class WorldQueryAcceptanceTest {
    @Test
    fun boundedApiReadsPreserveWorldStateAndRecoverAfterErrors() = runBlocking {
        fun environment(name: String) = getenv(name)?.toKString()?.takeIf { it.isNotBlank() }
        val serverLog = checkNotNull(environment("FACTORIO_MCP_UI_SERVER_LOG"))
        val clientLog = checkNotNull(environment("FACTORIO_MCP_UI_CLIENT_LOG"))
        val client = McpHttpClient(checkNotNull(environment("FACTORIO_MCP_ACCEPTANCE_URL")))
        val pid = checkNotNull(environment("FACTORIO_MCP_TEST_PID")).toInt()
        fun read(path: String) =
            SystemFileSystem.source(Path(path)).buffered().use { it.readString() }

        fun crcCount(path: String) = read(path).lineSequence().count { "UI_ACTION_FULL_CRC " in it }
        withTimeout(90_000) {
            try {
                client.initialize()
                client.tool("attach", buildJsonObject { put("pid", pid) }).toolValue()
                val playerRequest = buildJsonObject {
                    putJsonObject("selection") { put("kind", "player") }
                }
                val before = client.tool("world_query", playerRequest).toolValue()
                val player = before["player"]!!.jsonObject
                val position = player["position"]!!.jsonObject
                val entityRequest = buildJsonObject {
                    putJsonObject("selection") {
                        put("kind", "entities")
                        put("position", position)
                    }
                    putJsonArray("fields") {
                        listOf("name", "type", "unit_number", "quality", "amount", "train")
                            .forEach { add(it) }
                    }
                }
                val entities = client.tool("world_query", entityRequest).toolValue()
                val character =
                    entities["objects"]!!
                        .jsonArray
                        .map { it.jsonObject }
                        .single {
                            it["attributes"]!!.jsonObject["type"]?.jsonPrimitive?.content ==
                                    "character"
                        }
                assertEquals(
                    "normal",
                    character["attributes"]!!
                        .jsonObject["quality"]!!
                        .jsonObject["name"]!!
                        .jsonPrimitive
                        .content,
                )
                assertEquals(
                    "error",
                    character["read_status"]!!
                        .jsonObject["amount"]!!
                        .jsonObject["status"]!!
                        .jsonPrimitive
                        .content,
                )
                assertEquals(
                    "nil",
                    character["read_status"]!!
                        .jsonObject["train"]!!
                        .jsonObject["status"]!!
                        .jsonPrimitive
                        .content,
                )
                val unit = character["attributes"]!!.jsonObject["unit_number"]
                if (unit != null) {
                    val found =
                        client
                            .tool(
                                "world_query",
                                buildJsonObject {
                                    putJsonObject("selection") {
                                        put("kind", "entities")
                                        put("unit_number", unit)
                                    }
                                },
                            )
                            .toolValue()
                    assertEquals(
                        unit,
                        found["objects"]!!
                            .jsonArray
                            .single()
                            .jsonObject["attributes"]!!
                            .jsonObject["unit_number"],
                    )
                }
                val tiles =
                    client
                        .tool(
                            "world_query",
                            buildJsonObject {
                                putJsonObject("selection") {
                                    put("kind", "tiles")
                                    putJsonObject("area") {
                                        putJsonObject("left_top") {
                                            put("x", -0.5)
                                            put("y", -0.5)
                                        }
                                        putJsonObject("right_bottom") {
                                            put("x", 1)
                                            put("y", 1)
                                        }
                                    }
                                }
                                put("limit", 1)
                            },
                        )
                        .toolValue()
                assertTrue(tiles["truncated"]!!.jsonPrimitive.boolean)
                assertEquals(4, tiles["candidates_observed"]!!.jsonPrimitive.int)
                assertEquals(
                    -1.0,
                    tiles["objects"]!!
                        .jsonArray
                        .single()
                        .jsonObject["attributes"]!!
                        .jsonObject["position"]!!
                        .jsonObject["x"]!!
                        .jsonPrimitive
                        .double,
                )
                val boundaryTiles =
                    client
                        .tool(
                            "world_query",
                            buildJsonObject {
                                putJsonObject("selection") {
                                    put("kind", "tiles")
                                    putJsonObject("area") {
                                        putJsonObject("left_top") {
                                            put("x", -1)
                                            put("y", -1)
                                        }
                                        putJsonObject("right_bottom") {
                                            put("x", 2)
                                            put("y", 2)
                                        }
                                    }
                                }
                            },
                        )
                        .toolValue()
                        .getValue("objects")
                        .jsonArray
                assertEquals(9, boundaryTiles.size)
                for (tile in boundaryTiles) {
                    val batch = tile.jsonObject
                    val single =
                        client
                            .tool(
                                "world_query",
                                buildJsonObject {
                                    putJsonObject("selection") {
                                        put("kind", "tiles")
                                        put(
                                            "position",
                                            batch
                                                .getValue("attributes")
                                                .jsonObject
                                                .getValue("position"),
                                        )
                                    }
                                },
                            )
                            .toolValue()
                            .getValue("objects")
                            .jsonArray
                            .single()
                    assertEquals(
                        batch,
                        single,
                        "Batch and point reads must agree across positive and negative chunks",
                    )
                }
                repeat(2) {
                    val remote =
                        client
                            .tool(
                                "world_query",
                                buildJsonObject {
                                    putJsonObject("selection") {
                                        put("kind", "tiles")
                                        putJsonObject("position") {
                                            put("x", 900000)
                                            put("y", 900000)
                                        }
                                    }
                                },
                            )
                            .toolValue()["objects"]!!
                            .jsonArray
                            .single()
                            .jsonObject
                    assertEquals("ungenerated", remote["availability"]!!.jsonPrimitive.content)
                    assertFalse(
                        remote["visibility"]!!.jsonObject["generated"]!!.jsonPrimitive.boolean
                    )
                }
                val absentSurface =
                    client.tool(
                        "world_query",
                        buildJsonObject {
                            putJsonObject("selection") {
                                put("kind", "tiles")
                                put("position", position)
                            }
                            put("surface", "__mcp_absent_surface__")
                        },
                    )
                assertTrue(absentSurface["isError"]!!.jsonPrimitive.boolean)
                val empty =
                    client
                        .tool(
                            "world_query",
                            buildJsonObject {
                                putJsonObject("selection") {
                                    put("kind", "entities")
                                    putJsonObject("position") {
                                        put("x", 900000)
                                        put("y", 900000)
                                    }
                                }
                            },
                        )
                        .toolValue()
                assertTrue(empty.getValue("objects").jsonArray.isEmpty())
                repeat(20) {
                    client.tool("world_query", entityRequest).toolValue()
                    if (it % 5 == 0) client.tool("ui_read").toolValue()
                }
                val after = client.tool("world_query", playerRequest).toolValue()
                assertEquals(position, after["player"]!!.jsonObject["position"])
                assertEquals(player["index"], after["player"]!!.jsonObject["index"])
                val initialClientCrc = crcCount(clientLog)
                val initialServerCrc = crcCount(serverLog)
                while (
                    crcCount(clientLog) < initialClientCrc + 2 ||
                    crcCount(serverLog) < initialServerCrc + 2
                ) delay(100)
                assertFalse(read(clientLog).contains("desynchron", ignoreCase = true))
                assertFalse(read(serverLog).contains("desynchron", ignoreCase = true))
            } finally {
                client.close()
            }
        }
    }
}
