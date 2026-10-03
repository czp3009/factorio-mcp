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

class WorldOverviewAcceptanceTest {
    @Test
    fun viewportEntitiesMatchDetailedQueriesAndPreserveSynchronization() = runBlocking {
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
                val bounds = area(-10.0, -10.0, 10.0, 10.0)
                val fields = buildJsonArray {
                    add("name")
                    add("type")
                    add("position")
                    add("unit_number")
                    add("quality")
                }
                val request = buildJsonObject {
                    put("detail", "entities")
                    put("area", bounds)
                    put("entity_limit", 512)
                    put("fields", fields)
                }
                val selected = client.tool("world_overview", request).toolValue()
                assertFalse(selected.getValue("truncated").jsonPrimitive.boolean)
                val entities = client.tool("world_query", buildJsonObject {
                    putJsonObject("selection") {
                        put("kind", "entities")
                        put("area", bounds)
                    }
                    put("fields", fields)
                    put("limit", 512)
                }).toolValue()
                assertFalse(entities.getValue("truncated").jsonPrimitive.boolean)
                fun observations(value: JsonObject) = value.getValue("objects").jsonArray.map {
                    it.jsonObject.getValue("attributes").toString()
                }.sorted()
                assertEquals(observations(entities), observations(selected))
                assertTrue(selected.getValue("objects").jsonArray.isNotEmpty())
                val numericEntities = client.tool("world_query", buildJsonObject {
                    putJsonObject("selection") {
                        put("kind", "entities")
                        put("area", bounds)
                    }
                    putJsonArray("fields") {
                        add("position")
                        add("health")
                    }
                    put("limit", 512)
                }).toolValue()
                assertFalse(numericEntities.getValue("truncated").jsonPrimitive.boolean)
                val centered = numericEntities.getValue("objects").jsonArray.filter { entry ->
                    val position = entry.jsonObject.getValue("attributes").jsonObject.getValue("position").jsonObject
                    val x = position.getValue("x").jsonPrimitive.double
                    val y = position.getValue("y").jsonPrimitive.double
                    x >= -10 && x < 10 && y >= -10 && y < 10
                }
                val numeric = centered.mapNotNull {
                    it.jsonObject.getValue("attributes").jsonObject["health"]?.jsonPrimitive?.doubleOrNull
                }
                assertTrue(numeric.isNotEmpty())
                val summaryRequest = buildJsonObject {
                    put("area", bounds)
                    put("cell_size", 32)
                    put("entity_limit", 512)
                    put("group_by", JsonArray(emptyList()))
                    putJsonArray("aggregates") {
                        for (operation in listOf("sum", "min", "max")) add(buildJsonObject {
                            put("operation", operation)
                            put("field", "health")
                        })
                    }
                }
                val summary = client.tool("world_overview", summaryRequest).toolValue()
                assertFalse(summary.getValue("truncated").jsonPrimitive.boolean)
                val cell = summary.getValue("objects").jsonArray.single().jsonObject
                assertEquals(centered.size, cell.getValue("entities_observed").jsonPrimitive.int)
                val group = cell.getValue("entity_groups").jsonArray.single().jsonObject
                assertEquals(centered.size, group.getValue("count").jsonPrimitive.int)
                assertEquals(JsonObject(emptyMap()), group.getValue("attributes"))
                val calculations = group.getValue("aggregates").jsonArray.map { it.jsonObject }
                for ((index, expected) in listOf(numeric.sum(), numeric.min(), numeric.max()).withIndex()) {
                    val calculation = calculations[index]
                    assertEquals(expected, calculation.getValue("value").jsonPrimitive.double)
                    assertEquals(numeric.size, calculation.getValue("numeric_values").jsonPrimitive.int)
                    val excluded = listOf("nil_values", "read_errors", "non_numeric_values", "non_finite_values")
                        .sumOf { calculation.getValue(it).jsonPrimitive.int }
                    assertEquals(centered.size - numeric.size, excluded)
                }
                val empty = client.tool("world_overview", buildJsonObject {
                    put("detail", "entities")
                    put("area", area(900000.0, 900000.0, 900064.0, 900064.0))
                }).toolValue()
                assertTrue(empty.getValue("objects").jsonArray.isEmpty())
                assertFalse(empty.getValue("truncated").jsonPrimitive.boolean)
                val partial = client.tool("world_overview", buildJsonObject {
                    put("detail", "entities")
                    put("area", bounds)
                    put("entity_limit", 1)
                }).toolValue()
                assertTrue(partial.getValue("truncated").jsonPrimitive.boolean)
                assertEquals(1, partial.getValue("objects").jsonArray.size)
                val excessive = client.tool("world_overview", buildJsonObject {
                    put("area", area(-10.0, -10.0, 4100.0, 10.0))
                })
                assertTrue(excessive.getValue("isError").jsonPrimitive.boolean)
                repeat(12) {
                    client.tool("world_overview", request).toolValue()
                    client.tool("world_overview", summaryRequest).toolValue()
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
