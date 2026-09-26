@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

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

class WorldOverviewAcceptanceTest {
    @Test
    fun viewportAggregationMatchesDetailedQueriesAndPreservesSynchronization() = runBlocking {
        fun environment(name: String) = getenv(name)?.toKString()?.takeIf { it.isNotBlank() }
        val serverLog = environment("FACTORIO_MCP_UI_SERVER_LOG") ?: return@runBlocking
        val clientLog = checkNotNull(environment("FACTORIO_MCP_UI_CLIENT_LOG"))
        val client = McpHttpClient(checkNotNull(environment("FACTORIO_MCP_ACCEPTANCE_URL")))
        val pid = checkNotNull(environment("FACTORIO_MCP_TEST_PID")).toInt()
        fun read(path: String) =
            SystemFileSystem.source(Path(path)).buffered().use { it.readString() }

        fun crc(path: String) = read(path).lineSequence().count { "UI_ACTION_FULL_CRC " in it }
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
        withTimeout(90_000) {
            try {
                client.initialize()
                client.tool("attach", buildJsonObject { put("pid", pid) }).toolValue()
                val overview = client.tool("world_overview").toolValue()
                val viewport = overview.getValue("viewport").jsonObject
                assertTrue(viewport.getValue("available").jsonPrimitive.boolean)
                assertEquals(
                    overview.getValue("surface").jsonObject["index"],
                    viewport["surface_index"],
                )
                assertEquals(viewport["area"], overview["area"])
                val image = client.tool("screenshot").toolValue()
                assertEquals(image["width"], viewport["width"])
                assertEquals(image["height"], viewport["height"])
                assertTrue(overview.getValue("objects").jsonArray.size in 1..256)
                val bounds = area(-10.0, -10.0, 10.0, 10.0)
                val request = buildJsonObject {
                    put("area", bounds)
                    put("cell_size", 4)
                    put("entity_limit", 512)
                }
                val grid = client.tool("world_overview", request).toolValue()
                assertFalse(grid.getValue("truncated").jsonPrimitive.boolean)
                val entities =
                    client
                        .tool(
                            "world_query",
                            buildJsonObject {
                                putJsonObject("selection") {
                                    put("kind", "entities")
                                    put("area", bounds)
                                }
                                putJsonArray("fields") {
                                    add("name")
                                    add("type")
                                    add("position")
                                    add("quality")
                                }
                                put("limit", 512)
                            },
                        )
                        .toolValue()
                assertFalse(entities.getValue("truncated").jsonPrimitive.boolean)
                val expected =
                    entities
                        .getValue("objects")
                        .jsonArray
                        .map { it.jsonObject.getValue("attributes").jsonObject }
                        .filter {
                            val position = it.getValue("position").jsonObject
                            position.getValue("x").jsonPrimitive.double.let { x ->
                                x >= -10 && x < 10
                            } &&
                                    position.getValue("y").jsonPrimitive.double.let { y ->
                                        y >= -10 && y < 10
                                    }
                        }
                        .groupingBy {
                            listOf(
                                it.getValue("name"),
                                it.getValue("type"),
                                it.getValue("quality").jsonObject.getValue("name"),
                            )
                        }
                        .eachCount()
                val actual = mutableMapOf<List<JsonElement>, Int>()
                for (cell in grid.getValue("objects").jsonArray) {
                    val value = cell.jsonObject
                    assertEquals("complete", value.getValue("entity_scan").jsonPrimitive.content)
                    for (group in value.getValue("entity_groups").jsonArray) {
                        val record = group.jsonObject
                        val key =
                            listOf(
                                record.getValue("name"),
                                record.getValue("type"),
                                record.getValue("quality").jsonObject.getValue("name"),
                            )
                        actual[key] =
                            (actual[key] ?: 0) + record.getValue("count").jsonPrimitive.int
                    }
                }
                assertEquals(expected, actual)
                val empty =
                    client
                        .tool(
                            "world_overview",
                            buildJsonObject {
                                put("area", area(900000.0, 900000.0, 900064.0, 900064.0))
                                put("cell_size", 32)
                            },
                        )
                        .toolValue()
                for (cell in empty.getValue("objects").jsonArray) {
                    val value = cell.jsonObject
                    assertEquals(
                        0,
                        value
                            .getValue("coverage")
                            .jsonObject
                            .getValue("generated")
                            .jsonPrimitive
                            .int,
                    )
                    assertEquals(
                        "ungenerated",
                        value
                            .getValue("tile_sample")
                            .jsonObject
                            .getValue("availability")
                            .jsonPrimitive
                            .content,
                    )
                    assertTrue(value.getValue("entity_groups").jsonArray.isEmpty())
                }
                val partial =
                    client
                        .tool(
                            "world_overview",
                            buildJsonObject {
                                put("area", bounds)
                                put("cell_size", 32)
                                put("entity_limit", 1)
                            },
                        )
                        .toolValue()
                assertTrue(partial.getValue("truncated").jsonPrimitive.boolean)
                assertEquals(
                    "partial",
                    partial
                        .getValue("objects")
                        .jsonArray
                        .single()
                        .jsonObject
                        .getValue("entity_scan")
                        .jsonPrimitive
                        .content,
                )
                val excessive =
                    client.tool(
                        "world_overview",
                        buildJsonObject {
                            put("area", bounds)
                            put("cell_size", 1)
                        },
                    )
                assertTrue(excessive.getValue("isError").jsonPrimitive.boolean)
                repeat(12) {
                    client.tool("world_overview", request).toolValue()
                    client.tool("ui_read").toolValue()
                }
                val clientCrc = crc(clientLog)
                val serverCrc = crc(serverLog)
                while (crc(clientLog) < clientCrc + 2 || crc(serverLog) < serverCrc + 2) delay(100)
                assertFalse(read(clientLog).contains("desynchron", ignoreCase = true))
                assertFalse(read(serverLog).contains("desynchron", ignoreCase = true))
            } finally {
                client.close()
            }
        }
    }
}
