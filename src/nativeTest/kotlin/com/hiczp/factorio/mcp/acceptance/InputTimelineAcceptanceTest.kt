@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.acceptance

import com.hiczp.factorio.mcp.McpHttpClient
import com.hiczp.factorio.mcp.toolValue
import com.hiczp.factorio.mcp.uiSelector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.cinterop.toKString
import kotlinx.coroutines.*
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.*
import platform.posix.getenv

/** Requires the disposable ui-actions scenario with its timeline fixture buttons. */
class InputTimelineAcceptanceTest {
    @Test
    fun overlapsDwellRotationProjectionAndFailureCleanup() = runBlocking {
        fun environment(name: String) = checkNotNull(getenv(name)).toKString()
        val client = McpHttpClient(environment("FACTORIO_MCP_ACCEPTANCE_URL"))
        val pid = environment("FACTORIO_MCP_TEST_PID").toInt()
        val serverLog = environment("FACTORIO_MCP_UI_SERVER_LOG")
        val clientLog = environment("FACTORIO_MCP_UI_CLIENT_LOG")
        fun read(path: String) =
            SystemFileSystem.source(Path(path)).buffered().use { it.readString() }
        fun records(path: String, marker: String = "UI_ACTION_ACCEPTANCE ") =
            read(path)
                .lineSequence()
                .filter { marker in it }
                .map { Json.parseToJsonElement(it.substringAfter(marker)).jsonObject }
                .toList()
        fun kind(record: JsonObject) = record["kind"]?.jsonPrimitive?.content
        suspend fun awaitCondition(condition: () -> Boolean) =
            withTimeout(60_000) { while (!condition()) delay(50) }
        suspend fun click(text: String) =
            client
                .tool(
                    "ui_action",
                    buildJsonObject {
                        put("action", "click")
                        put("selector", uiSelector(text, "agui::TextButton"))
                    },
                )
                .toolValue()
        suspend fun input(vararg entries: JsonObject) =
            client
                .tool("input", buildJsonObject { put("timeline", JsonArray(entries.toList())) })
                .toolValue()
        fun key(name: String, ticks: String) = buildJsonObject {
            put("device", "keyboard")
            put("key", name)
            put("tick", ticks)
        }
        fun button(name: String, ticks: String) = buildJsonObject {
            put("device", "mouse")
            put("button", name)
            put("tick", ticks)
        }
        fun point(x: Double, y: Double, ticks: String) = buildJsonObject {
            put("device", "mouse")
            putJsonObject("position") {
                put("space", "world")
                put("x", x)
                put("y", y)
            }
            put("tick", ticks)
        }
        fun path(x: Int, y: Int, toX: Int, toY: Int, start: Int, dwell: Int?) = buildJsonObject {
            put("device", "mouse")
            putJsonObject("motion") {
                put("space", "world")
                put("snap", "tile_center")
                putJsonObject("from") {
                    put("x", x)
                    put("y", y)
                }
                putJsonObject("to") {
                    put("x", toX)
                    put("y", toY)
                }
            }
            if (dwell == null) put("tick", "$start-${start + 15}")
            else {
                put("start_tick", start)
                put("per_point_ticks", dwell)
            }
        }
        suspend fun prepare() {
            val initial = records(serverLog).size
            click("MCP timeline fixture")
            awaitCondition {
                val ready =
                    records(serverLog).drop(initial).lastOrNull { kind(it) == "timeline_ready" }
                ready != null && ready in records(clientLog)
            }
        }
        fun builtAfter(index: Int) =
            records(serverLog).drop(index).filter {
                kind(it) == "built" &&
                    it["data"]!!.jsonObject["name"]!!.jsonPrimitive.content == "transport-belt"
            }
        suspend fun playerPosition() =
            client
                .tool(
                    "world_query",
                    buildJsonObject {
                        putJsonObject("selection") { put("kind", "player") }
                        putJsonArray("fields") { add("position") }
                    },
                )
                .toolValue()["player"]!!
                .jsonObject["position"]!!
                .jsonObject
        withTimeout(600_000) {
            try {
                client.initialize()
                client.tool("attach", buildJsonObject { put("pid", pid) }).toolValue()
                val controls =
                    client
                        .tool(
                            "input_bindings",
                            buildJsonObject {
                                putJsonArray("ids") {
                                    add("move-right")
                                    add("rotate")
                                    add("build")
                                }
                            },
                        )
                        .toolValue()["controls"]!!
                        .jsonArray
                        .associate { row ->
                            val control = row.jsonObject
                            val binding =
                                control["bindings"]!!
                                    .jsonArray
                                    .map { it.jsonObject }
                                    .single {
                                        it["slot"]!!.jsonPrimitive.content ==
                                            "keyboard_mouse_primary"
                                    }
                            assertTrue(
                                binding["modifiers"]!!.jsonArray.isEmpty(),
                                "Fixture requires unmodified bindings",
                            )
                            control["id"]!!.jsonPrimitive.content to
                                binding["name"]!!.jsonPrimitive.content
                        }
                prepare()
                val initialWalk = records(serverLog, "INPUT_ACCEPTANCE ").size
                val overlap =
                    input(
                        key(controls.getValue("move-right"), "96-159,0-95,32-127,159"),
                        key(controls.getValue("move-right"), "48-143"),
                    )
                assertEquals("completed", overlap["status"]!!.jsonPrimitive.content)
                assertEquals(160, overlap["evaluated_ticks"]!!.jsonPrimitive.int)
                assertEquals(2, overlap["completed_entries"]!!.jsonPrimitive.int)
                awaitCondition {
                    val values = records(serverLog, "INPUT_ACCEPTANCE ").drop(initialWalk)
                    values.any { it["walking"]!!.jsonPrimitive.boolean } &&
                        values.lastOrNull()?.get("walking")?.jsonPrimitive?.boolean == false &&
                        records(clientLog, "INPUT_ACCEPTANCE ").containsAll(values)
                }
                val walking =
                    records(serverLog, "INPUT_ACCEPTANCE ").drop(initialWalk).dropWhile {
                        !it["walking"]!!.jsonPrimitive.boolean
                    }
                assertTrue(
                    walking.dropLast(1).all { it["walking"]!!.jsonPrimitive.boolean },
                    "An overlapping holder was released early",
                )
                val stationary = playerPosition()
                input(point(0.0, 0.0, "0-31"))
                assertEquals(stationary, playerPosition())
                println(
                    "factorio-mcp timeline: unordered overlapping intervals and final release observed"
                )

                // Calibrate the game's retained placement direction through ordinary input and an
                // authoritative event. The adapter does not infer or change placement semantics.
                prepare()
                var initial = records(serverLog).size
                input(point(-3.5, -2.5, "0-3"), button(controls.getValue("build"), "2-3"))
                awaitCondition { builtAfter(initial).isNotEmpty() }
                val direction =
                    builtAfter(initial).last()["data"]!!.jsonObject["direction"]!!.jsonPrimitive.int
                val rotations = ((4 - direction + 16) % 16) / 4
                if (rotations > 0)
                    input(
                        key(
                            controls.getValue("rotate"),
                            (0 until rotations).joinToString(",") { (it * 2).toString() },
                        )
                    )
                for (dwell in listOf(1, 3)) {
                    prepare()
                    initial = records(serverLog).size
                    val half = 8 * dwell
                    val result =
                        input(
                            path(-4, -3, 3, -3, 0, dwell),
                            button(controls.getValue("build"), "0-${half * 2 - 1}"),
                            button(controls.getValue("build"), "${2 * dwell}-${12 * dwell - 1}"),
                            key(controls.getValue("rotate"), half.toString()),
                            path(3, -3, 3, 4, half, dwell),
                        )
                    assertEquals("completed", result["status"]!!.jsonPrimitive.content)
                    assertEquals(half * 2, result["evaluated_ticks"]!!.jsonPrimitive.int)
                    assertEquals(5, result["completed_entries"]!!.jsonPrimitive.int)
                    val expected =
                        (-4..3).map { it + 0.5 to -2.5 }.toSet() +
                            (-2..4).map { 3.5 to it + 0.5 }.toSet()
                    fun positions() =
                        builtAfter(initial)
                            .map {
                                val p = it["data"]!!.jsonObject["position"]!!.jsonObject
                                p["x"]!!.jsonPrimitive.double to p["y"]!!.jsonPrimitive.double
                            }
                            .toSet()
                    awaitCondition {
                        positions() == expected &&
                            records(clientLog).containsAll(builtAfter(initial))
                    }
                    assertTrue(
                        builtAfter(initial).all { it["admin"]!!.jsonPrimitive.boolean == false }
                    )
                    val before = builtAfter(initial).size
                    input(point(-3.5, 4.5, "0-31"))
                    delay(1000)
                    assertEquals(
                        before,
                        builtAfter(initial).size,
                        "Mouse remained held after completion",
                    )
                    println(
                        "factorio-mcp timeline: L-shaped held drag, rotation, tile centers, dwell=$dwell; 15 server tiles"
                    )
                    // The gesture turns east to south. Restore east for the next pass.
                    input(key(controls.getValue("rotate"), "0,2,4"))
                }
                prepare()
                val failure =
                    input(
                        key(controls.getValue("move-right"), "0-31"),
                        point(999999.0, 999999.0, "1"),
                    )
                assertEquals("aborted", failure["status"]!!.jsonPrimitive.content)
                assertTrue(failure["reason"]!!.jsonPrimitive.content.isNotEmpty())
                // Advance past network delay before checking retained motion.
                input(point(0.0, 0.0, "0-63"))
                val stopped = playerPosition()
                input(point(0.0, 0.0, "0-31"))
                assertEquals(stopped, playerPosition(), "Failure did not release its key")
                val linear = input(path(-4, -3, 3, -3, 0, null))
                assertEquals(16, linear["evaluated_ticks"]!!.jsonPrimitive.int)
                assertEquals("completed", linear["status"]!!.jsonPrimitive.content)
                val crc = read(clientLog).lineSequence().count { "UI_ACTION_FULL_CRC " in it }
                awaitCondition {
                    read(clientLog).lineSequence().count { "UI_ACTION_FULL_CRC " in it } >= crc + 2
                }
                assertFalse(read(clientLog).contains("desynchron", true))
                assertFalse(read(serverLog).contains("desynchron", true))
                println(
                    "factorio-mcp timeline: world linear motion, offscreen failure cleanup and full CRC passed"
                )
            } finally {
                withContext(NonCancellable) {
                    try {
                        client
                            .tool(
                                "input",
                                buildJsonObject {
                                    put("stop_previous", true)
                                    putJsonArray("timeline") {}
                                },
                            )
                            .toolValue()
                        click("MCP timeline restore")
                        client.tool("detach").toolValue()
                    } finally {
                        client.close()
                    }
                }
            }
        }
    }
}
