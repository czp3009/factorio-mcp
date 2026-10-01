@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.acceptance

import com.hiczp.factorio.mcp.McpHttpClient
import com.hiczp.factorio.mcp.toolValue
import com.hiczp.factorio.mcp.uiSelector
import kotlinx.cinterop.toKString
import kotlinx.coroutines.*
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

/** Runs only against the explicitly selected local UI/input acceptance scenario. */
class InputAcceptanceTest {
    @Test
    fun exactTicksReplacementCancellationAndConcurrentObservations() = runBlocking {
        fun environment(name: String) = getenv(name)?.toKString()?.takeIf { it.isNotBlank() }
        val serverLog = environment("FACTORIO_MCP_UI_SERVER_LOG") ?: return@runBlocking
        val clientLog = checkNotNull(environment("FACTORIO_MCP_UI_CLIENT_LOG"))
        val pid = checkNotNull(environment("FACTORIO_MCP_TEST_PID")).toInt()
        val url = checkNotNull(environment("FACTORIO_MCP_ACCEPTANCE_URL"))
        fun read(path: String) =
            SystemFileSystem.source(Path(path)).buffered().use { it.readString() }

        fun records(path: String, marker: String = "INPUT_ACCEPTANCE ") =
            read(path)
                .lineSequence()
                .filter { marker in it }
                .map { Json.parseToJsonElement(it.substringAfter(marker)).jsonObject }
                .toList()

        fun operations(vararg steps: Pair<Long, List<String>>, stop: Boolean = false) =
            buildJsonObject {
                put("stop_previous", stop)
                putJsonArray("operations") {
                    steps.forEach { (ticks, keys) ->
                        add(
                            buildJsonObject {
                                put("ticks", ticks)
                                putJsonArray("controls") {
                                    keys.forEach { key ->
                                        add(
                                            buildJsonObject {
                                                put("device", "keyboard")
                                                put("key", key)
                                            }
                                        )
                                    }
                                }
                            }
                        )
                    }
                }
            }

        val client = McpHttpClient(url)
        val other = McpHttpClient(url)
        withTimeout(120_000) {
            try {
                client.initialize()
                other.initialize()
                client.tool("attach", buildJsonObject { put("pid", pid) }).toolValue()
                val initialClientInputs = records(clientLog).size
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

                suspend fun waitForWalking(
                    start: Int,
                    walking: Boolean,
                    minimum: Int = 1,
                ): List<JsonObject> =
                    withTimeout(10_000) {
                        while (true) {
                            val values = records(serverLog).drop(start)
                            val clientValues =
                                records(clientLog).drop(initialClientInputs).toHashSet()
                            // A held input keeps extending the server log ahead of the client.
                            // Observe their shared prefix instead of chasing the live server tail.
                            val confirmed = values.takeWhile { it in clientValues }
                            if (
                                confirmed.size >= minimum &&
                                values.last()["walking"]!!.jsonPrimitive.boolean == walking &&
                                confirmed.last()["walking"]!!.jsonPrimitive.boolean ==
                                walking &&
                                (walking || confirmed.size == values.size)
                            )
                                return@withTimeout confirmed
                            delay(20)
                        }
                        @Suppress("UNREACHABLE_CODE") emptyList()
                    }

                val bindings =
                    client
                        .tool(
                            "input_bindings",
                            buildJsonObject {
                                putJsonArray("ids") {
                                    add("move-up")
                                    add("move-right")
                                    add("move-down")
                                }
                            },
                        )
                        .toolValue()
                        .getValue("controls")
                        .jsonArray
                        .map { it.jsonObject }

                fun key(id: String): String {
                    val control = bindings.single { it["id"]!!.jsonPrimitive.content == id }
                    val binding =
                        (control["effective_bindings"] ?: control.getValue("bindings"))
                            .jsonArray
                            .map { it.jsonObject }
                            .first {
                                it["type"]!!.jsonPrimitive.content == "Keyboard" &&
                                        it["modifiers"]!!.jsonArray.isEmpty()
                            }
                    return binding.getValue("name").jsonPrimitive.content
                }

                val up = key("move-up")
                val right = key("move-right")
                val down = key("move-down")
                for (speed in listOf(1, 4)) {
                    val beforeClick = records(clientLog, "UI_ACTION_ACCEPTANCE ").size
                    click("MCP speed $speed")
                    val speedName = if (speed == 1) "speed_one" else "speed_four"
                    while (
                        records(clientLog, "UI_ACTION_ACCEPTANCE ").drop(beforeClick).none {
                            it["kind"]?.jsonPrimitive?.content == "click" &&
                                    it["data"]?.jsonObject?.get("name")?.jsonPrimitive?.content ==
                                    speedName
                        }
                    ) delay(20)
                    // The speed change also retimes multiplayer latency. Check exact effects only
                    // after both peers have crossed a synchronized checkpoint at the new speed.
                    val clientCrc =
                        read(clientLog).lineSequence().count { "UI_ACTION_FULL_CRC " in it }
                    val serverCrc =
                        read(serverLog).lineSequence().count { "UI_ACTION_FULL_CRC " in it }
                    while (
                        read(clientLog).lineSequence().count { "UI_ACTION_FULL_CRC " in it } <=
                        clientCrc ||
                        read(serverLog).lineSequence().count { "UI_ACTION_FULL_CRC " in it } <=
                        serverCrc
                    ) delay(50)
                    val start = records(serverLog).size
                    val result =
                        client
                            .tool(
                                "input",
                                operations(
                                    2L to listOf(up),
                                    3L to listOf(right),
                                    1L to emptyList(),
                                    2L to listOf(up, right),
                                    1L to listOf(up),
                                ),
                            )
                            .toolValue()
                    assertEquals("completed", result["status"]!!.jsonPrimitive.content)
                    assertEquals(9L, result["evaluated_ticks"]!!.jsonPrimitive.long)
                    assertEquals(5, result["completed_operations"]!!.jsonPrimitive.int)
                    val observed = waitForWalking(start, false, 10)
                    val walking = observed.filter { it["walking"]!!.jsonPrimitive.boolean }
                    assertEquals(
                        8,
                        walking.size,
                        "Unexpected authoritative held duration at speed $speed: $observed",
                    )
                    val firstTick = walking.first()["tick"]!!.jsonPrimitive.long
                    assertEquals(
                        listOf(0L, 1L, 2L, 3L, 4L, 6L, 7L, 8L),
                        walking.map { it["tick"]!!.jsonPrimitive.long - firstTick },
                    )
                    assertEquals(
                        listOf(0, 0, 4, 4, 4, 2, 2, 0),
                        walking.map { it["direction"]!!.jsonPrimitive.int },
                    )
                }
                click("MCP speed 1")
                val baseline = records(serverLog).size
                val hold = async {
                    client.tool("input", operations(100_000L to listOf(up))).toolValue()
                }
                waitForWalking(baseline, true)
                assertFalse(hold.isCompleted)
                other.tool("ui_read").toolValue()
                other
                    .tool(
                        "world_query",
                        buildJsonObject { putJsonObject("selection") { put("kind", "player") } },
                    )
                    .toolValue()
                assertTrue(
                    other
                        .tool("input", operations(1L to listOf(right)))["isError"]!!
                        .jsonPrimitive
                        .boolean
                )
                assertTrue(
                    other
                        .tool(
                            "input",
                            operations(1L to listOf("UNKNOWN_INPUT_TEST_KEY"), stop = true),
                        )["isError"]!!
                        .jsonPrimitive
                        .boolean
                )
                assertFalse(hold.isCompleted)
                val replace =
                    other.tool("input", operations(2L to listOf(right), stop = true)).toolValue()
                assertEquals("completed", replace["status"]!!.jsonPrimitive.content)
                assertEquals("aborted", hold.await()["status"]!!.jsonPrimitive.content)
                waitForWalking(baseline, false)

                val cancelStart = records(serverLog).size
                val cancel = async {
                    runCatching {
                        client.tool(
                            "input",
                            operations(100_000L to listOf(up)),
                            requestId = 100_000,
                        )
                    }
                }
                waitForWalking(cancelStart, true)
                client.request(
                    "notifications/cancelled",
                    buildJsonObject {
                        put("requestId", 100_000)
                        put("reason", "Acceptance cancellation")
                    },
                    notification = true,
                )
                waitForWalking(cancelStart, false)
                // SDK cancellation may abort the HTTP response; release and later calls are
                // authoritative.
                cancel.cancelAndJoin()
                other.tool("input", operations()).toolValue()

                val detachStart = records(serverLog).size
                val pending = async {
                    client.tool("input", operations(100_000L to listOf(up))).toolValue()
                }
                waitForWalking(detachStart, true)
                other.tool("detach").toolValue()
                assertEquals("aborted", pending.await()["status"]!!.jsonPrimitive.content)
                waitForWalking(detachStart, false)
                client.tool("attach", buildJsonObject { put("pid", pid) }).toolValue()

                val snapshot =
                    client.tool("ui_read", buildJsonObject { put("bounds", true) }).toolValue()
                val button =
                    snapshot["nodes"]!!
                        .jsonArray
                        .map { it.jsonObject }
                        .single { it["text"]?.jsonPrimitive?.content == "MCP normal" }
                val bounds = button.getValue("bounds").jsonObject
                val pointer = buildJsonObject {
                    put("device", "mouse")
                    putJsonObject("position") {
                        put("space", "viewport")
                        put(
                            "x",
                            bounds["x"]!!.jsonPrimitive.int +
                                    bounds["width"]!!.jsonPrimitive.int / 2,
                        )
                        put(
                            "y",
                            bounds["y"]!!.jsonPrimitive.int +
                                    bounds["height"]!!.jsonPrimitive.int / 2,
                        )
                    }
                }
                val clickStart = records(serverLog, "UI_ACTION_ACCEPTANCE ").size
                client
                    .tool(
                        "input",
                        buildJsonObject {
                            putJsonArray("operations") {
                                // Let frontend hover processing observe the new pointer before the
                                // click. A completed same-tick move/click need not activate the UI.
                                add(buildJsonObject { putJsonArray("controls") { add(pointer) } })
                                add(
                                    buildJsonObject {
                                        putJsonArray("controls") {
                                            add(
                                                buildJsonObject {
                                                    pointer.forEach { (name, value) ->
                                                        put(name, value)
                                                    }
                                                    put("button", "left")
                                                }
                                            )
                                        }
                                    }
                                )
                            }
                        },
                    )
                    .toolValue()
                while (
                    records(serverLog, "UI_ACTION_ACCEPTANCE ").drop(clickStart).none {
                        it["kind"]?.jsonPrimitive?.content == "click" &&
                                it["data"]?.jsonObject?.get("name")?.jsonPrimitive?.content == "normal"
                    }
                ) delay(20)
                // World selection requires a synthetic cursor to enter the client as well as move.
                // Long holds move a variable distance while concurrent reads complete. Restore the
                // fixture's vertical position so its chest is not projected behind the quickbar.
                withTimeout(20_000) {
                    while (true) {
                        val y =
                            other
                                .tool(
                                    "world_query",
                                    buildJsonObject {
                                        putJsonObject("selection") { put("kind", "player") }
                                        putJsonArray("fields") { add("position") }
                                    },
                                )
                                .toolValue()
                                .getValue("player")
                                .jsonObject
                                .getValue("position")
                                .jsonObject
                                .getValue("y")
                                .jsonPrimitive
                                .double
                        if (y in -3.0..3.0) break
                        other
                            .tool("input", operations(8L to listOf(if (y < 0) down else up)))
                            .toolValue()
                    }
                }
                val targetPosition =
                    other
                        .tool(
                            "world_query",
                            buildJsonObject {
                                putJsonObject("selection") {
                                    put("kind", "entities")
                                    put("name", "steel-chest")
                                    putJsonObject("position") {
                                        put("x", 6)
                                        put("y", 0)
                                    }
                                    put("radius", 2)
                                }
                                putJsonArray("fields") { add("position") }
                            },
                        )
                        .toolValue()
                        .getValue("objects")
                        .jsonArray
                        .single()
                        .jsonObject
                        .getValue("attributes")
                        .jsonObject
                        .getValue("position")
                        .jsonObject
                val view = other.tool("world_overview").toolValue().getValue("viewport").jsonObject
                val area = view.getValue("area").jsonObject
                val first = area.getValue("left_top").jsonObject
                val last = area.getValue("right_bottom").jsonObject
                fun pixel(axis: String, coordinate: Double, dimension: String): Int {
                    val low = first.getValue(axis).jsonPrimitive.double
                    val high = last.getValue(axis).jsonPrimitive.double
                    assertTrue(coordinate in low..high)
                    return ((coordinate - low) / (high - low) *
                            view.getValue(dimension).jsonPrimitive.int)
                        .toInt()
                }
                other
                    .tool(
                        "input",
                        buildJsonObject {
                            putJsonArray("operations") {
                                add(
                                    buildJsonObject {
                                        put("ticks", 2)
                                        putJsonArray("controls") {
                                            add(
                                                buildJsonObject {
                                                    put("device", "mouse")
                                                    putJsonObject("position") {
                                                        put("space", "viewport")
                                                        put(
                                                            "x",
                                                            pixel(
                                                                "x",
                                                                targetPosition
                                                                    .getValue("x")
                                                                    .jsonPrimitive
                                                                    .double,
                                                                "width",
                                                            ),
                                                        )
                                                        put(
                                                            "y",
                                                            pixel(
                                                                "y",
                                                                targetPosition
                                                                    .getValue("y")
                                                                    .jsonPrimitive
                                                                    .double,
                                                                "height",
                                                            ),
                                                        )
                                                    }
                                                }
                                            )
                                        }
                                    }
                                )
                            }
                        },
                    )
                    .toolValue()
                while (true) {
                    val player =
                        other
                            .tool(
                                "world_query",
                                buildJsonObject {
                                    putJsonObject("selection") { put("kind", "player") }
                                    putJsonArray("fields") { add("selected") }
                                },
                            )
                            .toolValue()
                            .getValue("objects")
                            .jsonArray
                            .single()
                            .jsonObject
                    val selected =
                        player.getValue("attributes").jsonObject["selected"] as? JsonObject
                    if (selected?.get("name")?.jsonPrimitive?.content == "steel-chest") {
                        assertEquals(targetPosition, selected.getValue("position"))
                        break
                    }
                    delay(20)
                }
                val crc = read(clientLog).lineSequence().count { "UI_ACTION_FULL_CRC " in it }
                while (
                    read(clientLog).lineSequence().count { "UI_ACTION_FULL_CRC " in it } < crc + 2
                ) delay(100)
                assertFalse(read(clientLog).contains("desynchron", true))
                assertFalse(read(serverLog).contains("desynchron", true))
            } finally {
                withContext(NonCancellable) {
                    runCatching { other.tool("input", operations(stop = true)) }
                    client.close()
                    other.close()
                }
            }
        }
    }
}
