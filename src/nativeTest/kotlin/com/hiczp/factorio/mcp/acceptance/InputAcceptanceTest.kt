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

/** Runs only against the explicitly selected local UI/input acceptance scenario. */
class InputAcceptanceTest {
    @Test
    fun dispatchTicksReplacementCancellationAndConcurrentObservations() = runBlocking {
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

        fun timeline(vararg steps: Pair<Long, List<String>>, stop: Boolean = false) =
            buildJsonObject {
                put("stop_previous", stop)
                putJsonArray("timeline") {
                    var tick = 0L
                    steps.forEach { (ticks, keys) ->
                        keys.forEach { key ->
                            addJsonObject {
                                put("device", "keyboard")
                                put("key", key)
                                put("tick", "$tick-${tick + ticks - 1}")
                            }
                        }
                        tick += ticks
                    }
                }
            }
        val client = McpHttpClient(url)
        val other = McpHttpClient(url)
        withTimeout(600_000) {
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

                fun directionChanges(values: List<JsonObject>): List<Int?> {
                    val changes = mutableListOf<Int?>()
                    for (value in values) {
                        val direction =
                            if (value["walking"]!!.jsonPrimitive.boolean)
                                value["direction"]!!.jsonPrimitive.int
                            else null
                        if (changes.isEmpty() || changes.last() != direction) changes += direction
                    }
                    return changes.dropWhile { it == null }
                }

                suspend fun waitForWalking(
                    start: Int,
                    walking: Boolean,
                    minimum: Int = 1,
                    expectedDirections: List<Int?>? = null,
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
                                    (walking || confirmed.size == values.size) &&
                                    (expectedDirections == null ||
                                        directionChanges(confirmed) == expectedDirections)
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
                    // Observe effects after both peers cross a checkpoint at the new speed.
                    // Local dispatch ticks do not prescribe authoritative multiplayer effect
                    // duration.
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
                                timeline(
                                    // Give slow rendering time to observe each hold; only local
                                    // progress has exact tick counts.
                                    64L to listOf(up),
                                    96L to listOf(right),
                                    32L to emptyList(),
                                    64L to listOf(up, right),
                                    64L to listOf(up),
                                ),
                            )
                            .toolValue()
                    assertEquals("completed", result["status"]!!.jsonPrimitive.content)
                    assertEquals(320L, result["evaluated_ticks"]!!.jsonPrimitive.long)
                    assertEquals(5, result["completed_entries"]!!.jsonPrimitive.int)
                    val observed =
                        waitForWalking(
                            start,
                            false,
                            expectedDirections = listOf(0, 4, null, 2, 0, null),
                        )
                    println(
                        "factorio-mcp input acceptance: speed=$speed local_ticks=320 authoritative_records=${observed.size}"
                    )
                }
                println(
                    "factorio-mcp input acceptance: local tick counts and authoritative direction changes passed at speeds 1 and 4"
                )
                click("MCP speed 1")
                val baseline = records(serverLog).size
                val hold = async {
                    client.tool("input", timeline(100_000L to listOf(up))).toolValue()
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
                        .tool("input", timeline(1L to listOf(right)))["isError"]!!
                        .jsonPrimitive
                        .boolean
                )
                assertTrue(
                    other.tool("input", timeline())["isError"]!!.jsonPrimitive.boolean,
                    "An empty request without replacement must preserve the active task",
                )
                assertTrue(
                    other
                        .tool(
                            "input",
                            timeline(1L to listOf("UNKNOWN_INPUT_TEST_KEY"), stop = true),
                        )["isError"]!!
                        .jsonPrimitive
                        .boolean
                )
                assertFalse(hold.isCompleted)
                val replace =
                    other.tool("input", timeline(2L to listOf(right), stop = true)).toolValue()
                assertEquals("completed", replace["status"]!!.jsonPrimitive.content)
                assertEquals("aborted", hold.await()["status"]!!.jsonPrimitive.content)
                waitForWalking(baseline, false)

                val cancelStart = records(serverLog).size
                val cancel = async {
                    runCatching {
                        client.tool("input", timeline(100_000L to listOf(up)), requestId = 100_000)
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
                other.tool("input", timeline()).toolValue()

                val detachStart = records(serverLog).size
                val pending = async {
                    client.tool("input", timeline(100_000L to listOf(up))).toolValue()
                }
                waitForWalking(detachStart, true)
                other.tool("detach").toolValue()
                assertEquals("aborted", pending.await()["status"]!!.jsonPrimitive.content)
                waitForWalking(detachStart, false)
                client.tool("attach", buildJsonObject { put("pid", pid) }).toolValue()
                println(
                    "factorio-mcp input acceptance: busy, replacement, cancellation and detach passed"
                )

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
                            putJsonArray("timeline") {
                                addJsonObject {
                                    pointer.forEach { (name, value) -> put(name, value) }
                                    put("tick", "0-32")
                                }
                                addJsonObject {
                                    put("device", "mouse")
                                    put("button", "left")
                                    put("tick", "32")
                                }
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
                println("factorio-mcp input acceptance: mouse click observed on the local server")
                // Reset through the scenario's normal synchronized GUI event. Returning by input
                // would make this pointer check depend on terrain and obstacles along the route.
                click("MCP reset position")
                withTimeout(120_000) {
                    while (true) {
                        val position =
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
                        val x = position.getValue("x").jsonPrimitive.double
                        val y = position.getValue("y").jsonPrimitive.double
                        if (x in -3.0..3.0 && y in -3.0..3.0) break
                        delay(20)
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
                other
                    .tool(
                        "input",
                        buildJsonObject {
                            putJsonArray("timeline") {
                                addJsonObject {
                                    put("device", "mouse")
                                    putJsonObject("position") {
                                        put("space", "world")
                                        put("x", targetPosition.getValue("x"))
                                        put("y", targetPosition.getValue("y"))
                                    }
                                    put("tick", "0-31")
                                }
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
                    runCatching { other.tool("input", timeline(stop = true)) }
                    runCatching {
                        other
                            .tool(
                                "ui_action",
                                buildJsonObject {
                                    put("action", "click")
                                    put("selector", uiSelector("MCP speed 1", "agui::TextButton"))
                                },
                            )
                            .toolValue()
                    }
                    client.close()
                    other.close()
                }
            }
        }
    }
}
