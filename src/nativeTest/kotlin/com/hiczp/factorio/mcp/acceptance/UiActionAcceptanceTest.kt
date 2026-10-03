@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.acceptance

import com.hiczp.factorio.mcp.McpHttpClient
import com.hiczp.factorio.mcp.toolValue
import com.hiczp.factorio.mcp.uiSelector
import kotlin.test.*
import kotlinx.cinterop.toKString
import kotlinx.coroutines.*
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.*
import platform.posix.getenv

/** Uses the existing Windows reference gestures on the isolated, non-admin acceptance scenario. */
class UiActionAcceptanceTest {
    @Test
    fun nativeGesturesHaveIndependentAuthoritativeEffectsAndFullCrc() = runBlocking {
        fun environment(name: String) = checkNotNull(getenv(name)).toKString()
        val serverLog = environment("FACTORIO_MCP_UI_SERVER_LOG")
        val clientLog = environment("FACTORIO_MCP_UI_CLIENT_LOG")
        val client = McpHttpClient(environment("FACTORIO_MCP_ACCEPTANCE_URL"))
        val pid = environment("FACTORIO_MCP_TEST_PID").toInt()
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
        fun crc(path: String) = read(path).lineSequence().count { "UI_ACTION_FULL_CRC " in it }
        var attached = false
        withTimeout(300_000) {
            try {
                client.initialize()
                client.tool("attach", buildJsonObject { put("pid", pid) }).toolValue()
                attached = true
                val initialCount = records(serverLog).size
                suspend fun waitFor(predicate: (List<JsonObject>) -> Boolean): List<JsonObject> {
                    while (true) {
                        val events = records(serverLog).drop(initialCount)
                        if (predicate(events) && records(clientLog).containsAll(events))
                            return events
                        delay(100)
                    }
                }
                suspend fun click(
                    text: String?,
                    type: String? = null,
                    modified: Boolean = false,
                    x: Double? = null,
                ) {
                    val result =
                        client
                            .tool(
                                "ui_action",
                                buildJsonObject {
                                    put("action", "click")
                                    put("selector", uiSelector(text, type))
                                    if (modified) {
                                        put("button", "right")
                                        putJsonArray("modifiers") {
                                            add("control")
                                            add("shift")
                                        }
                                    }
                                    if (x != null)
                                        putJsonObject("position") {
                                            put("x", x)
                                            put("y", 0.5)
                                        }
                                },
                            )
                            .toolValue()
                    assertEquals("completed", result.getValue("dispatch").jsonPrimitive.content)
                }
                suspend fun replace(before: String, after: String) {
                    val result =
                        client
                            .tool(
                                "ui_action",
                                buildJsonObject {
                                    put("action", "set_text")
                                    put("selector", uiSelector(before))
                                    put("text", after)
                                },
                            )
                            .toolValue()
                    assertEquals("completed", result.getValue("dispatch").jsonPrimitive.content)
                }
                suspend fun key(key: String, selected: Boolean = false, control: Boolean = false) {
                    client
                        .tool(
                            "ui_action",
                            buildJsonObject {
                                put("action", "press_key")
                                put("key", key)
                                if (selected) put("selector", uiSelector("MCP original"))
                                if (control) putJsonArray("modifiers") { add("control") }
                            },
                        )
                        .toolValue()
                }
                click("MCP recreate")
                waitFor { events -> events.any { it["kind"] == JsonPrimitive("created") } }
                click(null, "agui::Slider", x = 0.9)
                waitFor { events ->
                    events.any { event ->
                        event["kind"] == JsonPrimitive("checkpoint") &&
                            event["data"]?.jsonObject?.get("slider")?.jsonPrimitive?.double?.let {
                                it >= 80.0
                            } == true
                    }
                }
                click(null, "agui::Slider", x = 0.5)
                click("MCP option 1", "agui::DropDown")
                click("MCP option 80", "agui::TextButton")
                waitFor { events ->
                    events.any { event ->
                        val data = event["data"] as? JsonObject
                        event["kind"] == JsonPrimitive("selection") &&
                            data?.get("name") == JsonPrimitive("long_selection") &&
                            data["selected_index"] == JsonPrimitive(80)
                    }
                }
                key("A", selected = true, control = true)
                key("BACKSPACE")
                waitFor { events ->
                    events.any { event ->
                        event["kind"] == JsonPrimitive("checkpoint") &&
                            event["data"]?.jsonObject?.get("text") == JsonPrimitive("")
                    }
                }
                // Select the empty field through its window and original native text value.
                val empty = buildJsonObject {
                    putJsonArray("path") {
                        addJsonObject {
                            put("axis", "descendant")
                            putJsonObject("match") {
                                put("native_type", "agui::Window")
                                put("text", "MCP action fixture")
                            }
                        }
                        addJsonObject {
                            put("axis", "descendant")
                            putJsonObject("match") {
                                put("native_type", "agui::TextField")
                                put("text", "")
                            }
                        }
                    }
                }
                client
                    .tool(
                        "ui_action",
                        buildJsonObject {
                            put("action", "set_text")
                            put("selector", empty)
                            put("text", "MCP original")
                        },
                    )
                    .toolValue()
                click("MCP normal", modified = true)
                click("MCP checkbox", "agui::CheckBox")
                click("MCP toggle")
                click("MCP offscreen")
                replace("MCP original", "MCP 中文🚀")
                replace("123", "a9b8")
                replace("MCP read-only", "Must remain read-only")
                assertEquals(
                    JsonPrimitive(true),
                    client
                        .tool(
                            "ui_action",
                            buildJsonObject {
                                put("action", "click")
                                put("selector", uiSelector("MCP disabled"))
                            },
                        )
                        .getValue("isError"),
                )
                val events = waitFor { events ->
                    events.any { event ->
                        val data = event["data"] as? JsonObject
                        event["kind"] == JsonPrimitive("checkpoint") &&
                            data?.get("text") == JsonPrimitive("MCP 中文🚀") &&
                            data["numeric"] == JsonPrimitive("98") &&
                            data["readonly"] == JsonPrimitive("MCP read-only") &&
                            data["checked"] == JsonPrimitive(true) &&
                            data["toggle"] == JsonPrimitive(true)
                    }
                }
                assertTrue(
                    events.any { event ->
                        val data = event["data"] as? JsonObject
                        event["kind"] == JsonPrimitive("click") &&
                            data?.get("name") == JsonPrimitive("normal") &&
                            data["control"] == JsonPrimitive(true) &&
                            data["shift"] == JsonPrimitive(true)
                    }
                )
                assertTrue(
                    events.any { it["data"]?.jsonObject?.get("name") == JsonPrimitive("offscreen") }
                )
                assertTrue(
                    events.filter { "admin" in it }.all { it["admin"] == JsonPrimitive(false) }
                )
                click("MCP recreate")
                waitFor { events -> events.count { it["kind"] == JsonPrimitive("created") } >= 2 }
                click("MCP normal")
                waitFor { events ->
                    events.any { event ->
                        val data = event["data"] as? JsonObject
                        event["kind"] == JsonPrimitive("click") &&
                            data?.get("name") == JsonPrimitive("normal") &&
                            data["control"] == JsonPrimitive(false) &&
                            data["shift"] == JsonPrimitive(false)
                    }
                }
                val serverCrc = crc(serverLog)
                val clientCrc = crc(clientLog)
                while (crc(serverLog) < serverCrc + 2 || crc(clientLog) < clientCrc + 2) delay(100)
                assertFalse(read(serverLog).contains("desynchron", true))
                assertFalse(read(clientLog).contains("desynchron", true))
                println(
                    "factorio-mcp UI actions: native click, modifiers, slider capture, dropdown, filtered/read-only Unicode text, focused keys and recreation; authoritative events and full CRC"
                )
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
