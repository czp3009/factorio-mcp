@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.acceptance

import com.hiczp.factorio.mcp.McpHttpClient
import com.hiczp.factorio.mcp.toolValue
import kotlin.test.*
import kotlinx.cinterop.toKString
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import platform.posix.getenv

/** A disposable scenario with four numbered sprite buttons and their original SpritePath values. */
class UiIconAcceptanceTest {
    @Test
    fun readsStructuredReferencesAndOriginalLuaSpritePaths() = runBlocking {
        val client = McpHttpClient(checkNotNull(getenv("FACTORIO_MCP_ACCEPTANCE_URL")).toKString())
        val pid = checkNotNull(getenv("FACTORIO_MCP_TEST_PID")).toKString().toInt()
        var attached = false
        withTimeout(600_000) {
            try {
                client.initialize()
                client.tool("attach", buildJsonObject { put("pid", pid) }).toolValue()
                attached = true
                val observation =
                    client
                        .tool(
                            "ui_read",
                            buildJsonObject {
                                putJsonObject("selector") {
                                    putJsonArray("path") {
                                        add(
                                            buildJsonObject {
                                                put("axis", "descendant")
                                                putJsonObject("match") {
                                                    put("native_type", "agui::Window")
                                                    put("text", "MCP number fixture")
                                                }
                                            }
                                        )
                                    }
                                }
                            },
                        )
                        .toolValue()
                assertFalse(observation.getValue("truncated").jsonPrimitive.boolean)
                assertWidgetIconReferences(observation)
                val buttons =
                    observation
                        .getValue("nodes")
                        .jsonArray
                        .map { it.jsonObject }
                        .filter { it["type"]?.jsonPrimitive?.content == "IconButtonWithNumber" }
                assertEquals(4, buttons.size)
                for (button in buttons) {
                    val icons =
                        button.getValue("properties").jsonObject.getValue("icons").jsonObject
                    assertNotEquals(JsonNull, icons.getValue("normal"))
                    assertFalse(
                        icons.getValue("normal").jsonObject["truncated"] == JsonPrimitive(true)
                    )
                }
                for ((name, sprite) in
                    listOf(
                        "finite" to "item/iron-plate",
                        "zero" to "item/copper-plate",
                        "negative" to "item/steel-plate",
                        "suppressed" to "item/stone",
                    )) {
                    val api =
                        client
                            .tool(
                                "world_query",
                                buildJsonObject {
                                    putJsonObject("selection") {
                                        put("kind", "inspect")
                                        putJsonObject("target") { put("kind", "player") }
                                        putJsonArray("path") {
                                            add(buildJsonObject { put("property", "gui") })
                                            add(buildJsonObject { put("property", "screen") })
                                            add(
                                                buildJsonObject {
                                                    put("index", "mcp_number_fixture")
                                                }
                                            )
                                            add(
                                                buildJsonObject { put("index", "mcp_number_$name") }
                                            )
                                        }
                                    }
                                    putJsonArray("fields") {
                                        add("name")
                                        add("sprite")
                                        add("hovered_sprite")
                                        add("clicked_sprite")
                                    }
                                },
                            )
                            .toolValue()
                            .getValue("objects")
                            .jsonArray
                            .single()
                            .jsonObject
                    assertEquals("LuaGuiElement", api.getValue("object_name").jsonPrimitive.content)
                    assertTrue(api.getValue("read_status").jsonObject.isEmpty())
                    val attributes = api.getValue("attributes").jsonObject
                    assertEquals(
                        "mcp_number_$name",
                        attributes.getValue("name").jsonPrimitive.content,
                    )
                    assertEquals(sprite, attributes.getValue("sprite").jsonPrimitive.content)
                    assertEquals("", attributes.getValue("hovered_sprite").jsonPrimitive.content)
                    assertEquals("", attributes.getValue("clicked_sprite").jsonPrimitive.content)
                }
                println(
                    "factorio-mcp icons: four structured widget references and original Lua SpritePath values; no render graph"
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

internal fun assertWidgetIconReferences(observation: JsonObject) {
    assertNull(observation["icons_unavailable_reason"])
    assertFalse("sprites" in observation)
    val icons =
        observation.getValue("nodes").jsonArray.mapNotNull {
            (it.jsonObject["properties"] as? JsonObject)?.get("icons") as? JsonObject
        }
    assertTrue(icons.isNotEmpty())
    for (icon in icons) {
        assertEquals(setOf("normal", "hovered", "disabled"), icon.keys)
        for (state in icon.values) {
            if (state == JsonNull) continue
            val value = state.jsonObject
            val reference = value.getValue("reference")
            if (reference == JsonNull) assertEquals(JsonPrimitive(true), value["truncated"])
            else {
                assertTrue(reference.jsonPrimitive.int in 0 until 512)
                assertEquals(JsonPrimitive("snapshot"), value["identity_scope"])
            }
            for (field in
                listOf(
                    "filename",
                    "width",
                    "height",
                    "scale",
                    "shift",
                    "tint",
                    "next",
                    "extra",
                )) assertFalse(field in value)
        }
    }
}
