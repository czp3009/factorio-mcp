@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.acceptance

import com.hiczp.factorio.mcp.McpHttpClient
import com.hiczp.factorio.mcp.assertUiCaptureReleased
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

/** Mouse fallback coverage against the explicitly selected local UI acceptance scenario. */
class MouseMotionAcceptanceTest {
    @Test
    fun heldMotionMovesWidgetsAndCancellationDiscardsFuturePoints() = runBlocking {
        fun environment(name: String) = getenv(name)?.toKString()?.takeIf { it.isNotBlank() }
        val serverLog = environment("FACTORIO_MCP_UI_SERVER_LOG") ?: return@runBlocking
        val clientLog = checkNotNull(environment("FACTORIO_MCP_UI_CLIENT_LOG"))
        val pid = checkNotNull(environment("FACTORIO_MCP_TEST_PID")).toInt()
        val url = checkNotNull(environment("FACTORIO_MCP_ACCEPTANCE_URL"))
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

        fun point(x: Int, y: Int) = buildJsonObject {
            put("space", "viewport")
            put("x", x)
            put("y", y)
        }

        fun gesture(start: JsonObject, end: JsonObject, ticks: Int = 6, motionTick: Int = 3) =
            buildJsonObject {
                putJsonArray("timeline") {
                    addJsonObject {
                        put("device", "mouse")
                        put("position", start)
                        put("tick", "0-${motionTick}")
                    }
                    addJsonObject {
                        put("device", "mouse")
                        put("button", "left")
                        put("tick", "2-${ticks + 1}")
                    }
                    addJsonObject {
                        put("device", "mouse")
                        putJsonObject("motion") {
                            put("space", "viewport")
                            val point = buildJsonObject {
                                put("x", end.getValue("x"))
                                put("y", end.getValue("y"))
                            }
                            put("from", point)
                            put("to", point)
                        }
                        put("tick", "${motionTick + 1}-${ticks + 1}")
                    }
                }
            }
        withTimeout(120_000) {
            val client = McpHttpClient(url)
            val other = McpHttpClient(url)
            try {
                client.initialize()
                other.initialize()
                client.tool("attach", buildJsonObject { put("pid", pid) }).toolValue()
                suspend fun click(text: String) =
                    other
                        .tool(
                            "ui_action",
                            buildJsonObject {
                                put("action", "click")
                                put("selector", uiSelector(text, "agui::TextButton"))
                            },
                        )
                        .toolValue()

                suspend fun nodes() =
                    other
                        .tool("ui_read", buildJsonObject { put("bounds", true) })
                        .toolValue()
                        .getValue("nodes")
                        .jsonArray
                        .map { it.jsonObject }

                suspend fun slider() =
                    nodes().single { it["type"]?.jsonPrimitive?.content == "agui::Slider" }

                suspend fun window() =
                    nodes()
                        .single {
                            it["type"]?.jsonPrimitive?.content == "agui::Window" &&
                                it["text"]?.jsonPrimitive?.content == "MCP action fixture"
                        }
                        .getValue("bounds")
                        .jsonObject

                fun JsonObject.integer(name: String) = getValue(name).jsonPrimitive.int
                suspend fun checkpoint(predicate: (JsonObject) -> Boolean): JsonObject {
                    while (true) {
                        val event =
                            records(serverLog).lastOrNull {
                                it["kind"]?.jsonPrimitive?.content == "checkpoint" &&
                                    predicate(it.getValue("data").jsonObject)
                            }
                        if (
                            event != null &&
                                event in records(clientLog) &&
                                "UI_ACTION_FULL_CRC ${event.getValue("tick").jsonPrimitive.long}" in
                                    read(clientLog)
                        )
                            return event
                        delay(50)
                    }
                }
                click("MCP speed 1")
                val beforeRecreation = records(serverLog).size
                click("MCP recreate")
                while (
                    records(serverLog).drop(beforeRecreation).none {
                        it["kind"]?.jsonPrimitive?.content == "created" && it in records(clientLog)
                    }
                ) delay(20)
                val bounds = slider().getValue("bounds").jsonObject
                val center =
                    point(
                        bounds.integer("x") + bounds.integer("width") / 2,
                        bounds.integer("y") + bounds.integer("height") / 2,
                    )
                val end =
                    point(
                        bounds.integer("x") + bounds.integer("width") * 85 / 100,
                        center.integer("y"),
                    )
                val result = client.tool("input", gesture(center, end)).toolValue()
                assertEquals("completed", result.getValue("status").jsonPrimitive.content)
                assertEquals(8L, result.getValue("evaluated_ticks").jsonPrimitive.long)
                checkpoint { it.getValue("slider").jsonPrimitive.double >= 80.0 }
                assertTrue(
                    slider()
                        .getValue("properties")
                        .jsonObject
                        .getValue("value")
                        .jsonPrimitive
                        .double >= 80.0
                )

                val before = window()
                val title =
                    point(
                        before.integer("x") + before.integer("width") - 40,
                        before.integer("y") + 25,
                    )
                val destination = point(title.integer("x") + 160, title.integer("y") + 60)
                client.tool("input", gesture(title, destination)).toolValue()
                val after = window()
                assertEquals(before.integer("x") + 160, after.integer("x"))
                assertEquals(before.integer("y") + 60, after.integer("y"))
                checkpoint {
                    it["location"]?.jsonObject?.get("x")?.jsonPrimitive?.int ==
                        after.integer("x") &&
                        it["location"]?.jsonObject?.get("y")?.jsonPrimitive?.int ==
                            after.integer("y")
                }

                val moved = slider().getValue("bounds").jsonObject
                val low =
                    point(
                        moved.integer("x") + moved.integer("width") / 4,
                        moved.integer("y") + moved.integer("height") / 2,
                    )
                val high =
                    point(moved.integer("x") + moved.integer("width") * 9 / 10, low.integer("y"))
                val held = async {
                    runCatching {
                        client.tool(
                            "input",
                            gesture(low, high, ticks = 1000, motionTick = 600),
                            requestId = 230_000,
                        )
                    }
                }
                while (
                    slider()
                        .getValue("properties")
                        .jsonObject
                        .getValue("value")
                        .jsonPrimitive
                        .double >= 40.0
                ) delay(20)
                client.request(
                    "notifications/cancelled",
                    buildJsonObject {
                        put("requestId", 230_000)
                        put("reason", "Cancel held mouse before scheduled motion")
                    },
                    notification = true,
                )
                held.cancelAndJoin()
                // Do not replace the task: successful ordinary admission proves notification
                // cleanup.
                while (true) {
                    val probe = other.tool("input", buildJsonObject { putJsonArray("timeline") {} })
                    if (probe["isError"]?.jsonPrimitive?.boolean != true) {
                        probe.toolValue()
                        break
                    }
                    delay(20)
                }
                assertUiCaptureReleased(pid.toUInt())
                click("MCP speed 4")
                other
                    .tool(
                        "input",
                        buildJsonObject {
                            putJsonArray("timeline") {
                                addJsonObject {
                                    put("device", "mouse")
                                    put("position", low)
                                    put("tick", "0-649")
                                }
                            }
                        },
                    )
                    .toolValue()
                assertTrue(
                    slider()
                        .getValue("properties")
                        .jsonObject
                        .getValue("value")
                        .jsonPrimitive
                        .double < 40.0
                )
                checkpoint { it.getValue("slider").jsonPrimitive.double < 40.0 }
                click("MCP speed 1")
                click("MCP recreate")
                assertFalse(read(clientLog).contains("desynchron", ignoreCase = true))
            } finally {
                client.close()
                other.close()
            }
        }
    }
}
