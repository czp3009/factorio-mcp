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

/** Explicit disposable scenario with four numbered sprite buttons; observes existing objects only. */
class UiNumberAcceptanceTest {
    @Test
    fun comparesNativeNumberInterfaceWithExistingLuaGuiElements() = runBlocking {
        val client = McpHttpClient(checkNotNull(getenv("FACTORIO_MCP_ACCEPTANCE_URL")).toKString())
        val pid = checkNotNull(getenv("FACTORIO_MCP_TEST_PID")).toKString().toInt()
        var attached = false
        withTimeout(600_000) {
            try {
                client.initialize()
                client.tool("attach", buildJsonObject { put("pid", pid) }).toolValue()
                attached = true
                val observation = client.tool("ui_read", buildJsonObject {
                    putJsonObject("selector") {
                        putJsonArray("path") {
                            add(buildJsonObject {
                                put("axis", "descendant")
                                putJsonObject("match") {
                                    put("native_type", "agui::Window")
                                    put("text", "MCP number fixture")
                                }
                            })
                        }
                    }
                }).toolValue()
                assertFalse(observation.getValue("truncated").jsonPrimitive.boolean)
                val buttons = observation.getValue("nodes").jsonArray.map { it.jsonObject }
                    .filter { it["type"]?.jsonPrimitive?.content == "IconButtonWithNumber" }
                assertEquals(4, buttons.size)
                val names = listOf("finite", "zero", "negative", "suppressed")
                val expected = listOf(12345.125, 0.0, -0.25, null)
                names.forEachIndexed { index, name ->
                    val api = client.tool("world_query", buildJsonObject {
                        putJsonObject("selection") {
                            put("kind", "inspect")
                            putJsonObject("target") { put("kind", "player") }
                            putJsonArray("path") {
                                add(buildJsonObject { put("property", "gui") })
                                add(buildJsonObject { put("property", "screen") })
                                add(buildJsonObject { put("index", "mcp_number_fixture") })
                                add(buildJsonObject { put("index", "mcp_number_$name") })
                            }
                        }
                        putJsonArray("fields") {
                            add("name")
                            add("number")
                        }
                    }).toolValue().getValue("objects").jsonArray.single().jsonObject
                    assertEquals("LuaGuiElement", api.getValue("object_name").jsonPrimitive.content)
                    val readStatus = api.getValue("read_status").jsonObject
                    val attributes = api.getValue("attributes").jsonObject
                    assertEquals("mcp_number_$name", attributes.getValue("name").jsonPrimitive.content)
                    val number = buttons[index].getValue("properties").jsonObject.getValue("number").jsonObject
                    val value = expected[index]
                    if (value == null) {
                        assertEquals(setOf("number"), readStatus.keys)
                        assertEquals("nil", readStatus.getValue("number").jsonObject.getValue("status").jsonPrimitive.content)
                        assertTrue(attributes["number"] == null || attributes["number"] == JsonNull)
                        assertFalse(number.getValue("draw_requested").jsonPrimitive.boolean)
                        assertEquals(JsonNull, number.getValue("value"))
                        for (flag in listOf("show_zero", "unknown", "infinite")) assertFalse(flag in number)
                    } else {
                        assertTrue(readStatus.isEmpty())
                        assertEquals(value, attributes.getValue("number").jsonPrimitive.double)
                        assertEquals(value, number.getValue("value").jsonPrimitive.double)
                        assertTrue(number.getValue("draw_requested").jsonPrimitive.boolean)
                        assertTrue(number.getValue("show_zero").jsonPrimitive.boolean)
                        assertFalse(number.getValue("unknown").jsonPrimitive.boolean)
                        assertFalse(number.getValue("infinite").jsonPrimitive.boolean)
                    }
                    println("factorio-mcp number $name: $number; independently compared with LuaGuiElement.number")
                }
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
