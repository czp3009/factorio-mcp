@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.acceptance

import com.hiczp.factorio.mcp.McpHttpClient
import com.hiczp.factorio.mcp.toolValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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

/** Uses ordinary copy/build controls; only the fixture supplies the original machine. */
class BlueprintMotionAcceptanceTest {
    @Test
    fun copySelectionAndContinuousBlueprintPlacementReachTheServer() = runBlocking {
        fun environment(name: String) = getenv(name)?.toKString()?.takeIf { it.isNotBlank() }
        val serverLog = environment("FACTORIO_MCP_UI_SERVER_LOG") ?: return@runBlocking
        val clientLog = checkNotNull(environment("FACTORIO_MCP_UI_CLIENT_LOG"))
        val pid = checkNotNull(environment("FACTORIO_MCP_TEST_PID")).toInt()
        val client = McpHttpClient(checkNotNull(environment("FACTORIO_MCP_ACCEPTANCE_URL")))
        fun read(path: String) =
            SystemFileSystem.source(Path(path)).buffered().use { it.readString() }

        fun records(path: String) =
            read(path)
                .lineSequence()
                .filter { "UI_ACTION_ACCEPTANCE " in it }
                .map {
                    Json.parseToJsonElement(it.substringAfter("UI_ACTION_ACCEPTANCE ")).jsonObject
                }
                .toList()
        withTimeout(90_000) {
            try {
                client.initialize()
                client.tool("attach", buildJsonObject { put("pid", pid) }).toolValue()
                val bindings =
                    client
                        .tool(
                            "input_bindings",
                            buildJsonObject {
                                putJsonArray("ids") {
                                    listOf("copy", "clear-cursor", "build").forEach { add(it) }
                                }
                            },
                        )
                        .toolValue()
                        .getValue("controls")
                        .jsonArray
                        .map { it.jsonObject }
                        .associateBy { it.getValue("id").jsonPrimitive.content }

                fun controls(id: String): List<JsonObject> {
                    val binding =
                        bindings
                            .getValue(id)
                            .getValue("bindings")
                            .jsonArray
                            .map { it.jsonObject }
                            .single {
                                it.getValue("slot").jsonPrimitive.content ==
                                    "keyboard_mouse_primary"
                            }
                    val modifiers =
                        binding.getValue("modifiers").jsonArray.map { it.jsonPrimitive.content }
                    return modifiers.map { modifier ->
                        buildJsonObject {
                            put("device", "keyboard")
                            put(
                                "key",
                                mapOf("control" to "LCTRL", "shift" to "LSHIFT", "alt" to "LALT")
                                    .getValue(modifier),
                            )
                        }
                    } +
                        buildJsonObject {
                            val keyboard =
                                binding.getValue("type").jsonPrimitive.content == "Keyboard"
                            put("device", if (keyboard) "keyboard" else "mouse")
                            put(if (keyboard) "key" else "button", binding.getValue("name"))
                        }
                }

                suspend fun input(controls: List<JsonObject>) =
                    client
                        .tool(
                            "input",
                            buildJsonObject {
                                putJsonArray("timeline") {
                                    controls.forEach { control ->
                                        addJsonObject {
                                            control.forEach { (name, value) -> put(name, value) }
                                            put("tick", "0")
                                        }
                                    }
                                }
                            },
                        )
                        .toolValue()

                suspend fun cursor(): String? {
                    val result =
                        client
                            .tool(
                                "world_query",
                                buildJsonObject {
                                    putJsonObject("selection") { put("kind", "player") }
                                    putJsonArray("fields") { add("cursor_stack") }
                                },
                            )
                            .toolValue()
                    return result
                        .getValue("objects")
                        .jsonArray
                        .single()
                        .jsonObject
                        .getValue("attributes")
                        .jsonObject
                        .getValue("cursor_stack")
                        .jsonObject["name"]
                        ?.jsonPrimitive
                        ?.content
                }
                input(controls("clear-cursor"))
                while (cursor() != null) delay(20)
                fun point(x: Double, y: Double) = buildJsonObject {
                    put("space", "world")
                    put("x", x)
                    put("y", y)
                }
                suspend fun gesture(start: JsonObject, points: List<JsonObject>) =
                    client
                        .tool(
                            "input",
                            buildJsonObject {
                                putJsonArray("timeline") {
                                    addJsonObject {
                                        put("device", "mouse")
                                        put("position", start)
                                        put("tick", "0-1")
                                    }
                                    controls("build").forEach { control ->
                                        addJsonObject {
                                            control.forEach { (name, value) -> put(name, value) }
                                            put("tick", "2-${3 * points.size + 5}")
                                        }
                                    }
                                    points.forEachIndexed { index, point ->
                                        addJsonObject {
                                            put("device", "mouse")
                                            put("position", point)
                                            val tick = 2 + 3 * (index + 1) - 1
                                            put("tick", "$tick-${tick + 2}")
                                        }
                                    }
                                }
                            },
                        )
                        .toolValue()
                input(controls("copy"))
                while (cursor() != "copy-paste-tool") delay(20)
                gesture(point(10.8, -10.2), listOf(point(14.2, -6.8)))
                while (cursor() != "blueprint") delay(20)
                val initial = records(serverLog).size
                val expectedX = listOf(-4.5, -1.5, 1.5, 4.5)
                val result =
                    gesture(point(expectedX.first(), 8.5), expectedX.drop(1).map { point(it, 8.5) })
                assertEquals("completed", result.getValue("status").jsonPrimitive.content)
                assertEquals(15L, result.getValue("evaluated_ticks").jsonPrimitive.long)
                var built: List<JsonObject>
                while (true) {
                    built =
                        records(serverLog).drop(initial).filter {
                            it["kind"]?.jsonPrimitive?.content == "built" &&
                                it["data"]?.jsonObject?.get("ghost_name")?.jsonPrimitive?.content ==
                                    "assembling-machine-1"
                        }
                    if (built.size >= 4 && records(clientLog).containsAll(built)) break
                    delay(50)
                }
                assertEquals(
                    expectedX,
                    built
                        .map {
                            it.getValue("data")
                                .jsonObject
                                .getValue("position")
                                .jsonObject
                                .getValue("x")
                                .jsonPrimitive
                                .double
                        }
                        .sorted(),
                )
                assertTrue(
                    built.all {
                        it.getValue("data")
                            .jsonObject
                            .getValue("position")
                            .jsonObject
                            .getValue("y")
                            .jsonPrimitive
                            .double == 8.5
                    }
                )
                val observed =
                    client
                        .tool(
                            "world_query",
                            buildJsonObject {
                                putJsonObject("selection") {
                                    put("kind", "entities")
                                    put("type", "entity-ghost")
                                    putJsonObject("area") {
                                        putJsonObject("left_top") {
                                            put("x", -7)
                                            put("y", 6)
                                        }
                                        putJsonObject("right_bottom") {
                                            put("x", 7)
                                            put("y", 11)
                                        }
                                    }
                                }
                                putJsonArray("fields") {
                                    add("ghost_name")
                                    add("position")
                                }
                            },
                        )
                        .toolValue()
                        .getValue("objects")
                        .jsonArray
                        .map { it.jsonObject.getValue("attributes").jsonObject }
                assertEquals(4, observed.size)
                assertTrue(
                    observed.all {
                        it.getValue("ghost_name").jsonPrimitive.content == "assembling-machine-1"
                    }
                )
                val lastTick = built.maxOf { it.getValue("tick").jsonPrimitive.long }
                while (
                    !read(clientLog).lineSequence().any {
                        "UI_ACTION_FULL_CRC " in it &&
                            it.substringAfter("UI_ACTION_FULL_CRC ").trim().toLong() >= lastTick
                    }
                ) delay(50)
                assertFalse(read(clientLog).contains("desynchron", ignoreCase = true))
                input(controls("clear-cursor"))
                while (cursor() != null) delay(20)
                client.tool("screenshot").toolValue()
                Unit
            } finally {
                client.close()
            }
        }
    }
}
