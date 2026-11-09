@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.acceptance

import com.hiczp.factorio.mcp.McpHttpClient
import com.hiczp.factorio.mcp.toolValue
import com.hiczp.factorio.mcp.uiSelectorForNode
import kotlin.test.*
import kotlinx.cinterop.toKString
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import platform.posix.getenv

/** A loaded disposable world with its ordinary SideMenu; observes without moving the pointer. */
class UiResourceAcceptanceTest {
    @Test
    fun readsRetainedImageIdentityWithoutFramesOrScripts() = runBlocking {
        val client = McpHttpClient(checkNotNull(getenv("FACTORIO_MCP_ACCEPTANCE_URL")).toKString())
        val pid = checkNotNull(getenv("FACTORIO_MCP_TEST_PID")).toKString().toInt()
        var attached = false
        withTimeout(600_000) {
            try {
                client.initialize()
                client.tool("attach", buildJsonObject { put("pid", pid) }).toolValue()
                attached = true
                val value = client.tool("ui_read", buildJsonObject {
                    putJsonObject("selector") {
                        putJsonArray("path") { addJsonObject {
                            put("axis", "descendant")
                            putJsonObject("match") { put("native_type", "agui::ImageWidget") }
                        } }
                    }
                    put("max_depth", 0)
                    putJsonArray("fields") { add("properties.image") }
                }).toolValue()
                assertNull(value["image_unavailable_reason"])
                assertNull(value["icon_names_unavailable_reason"])
                val images = value.getValue("nodes").jsonArray.mapNotNull {
                    (it.jsonObject["properties"] as? JsonObject)?.get("image") as? JsonObject
                }
                assertTrue(images.isNotEmpty(), "The loaded stock world must contain image widgets")
                assertTrue(images.any { it["native_type"] == JsonPrimitive("GuiImage") && "sprite" in it })
                for (image in images) {
                    assertTrue(image.keys.all { it in setOf("native_type", "sprite") })
                    if (image["native_type"] != JsonPrimitive("GuiImage")) assertFalse("sprite" in image)
                    (image["sprite"] as? JsonObject)?.let { sprite ->
                        assertEquals(JsonPrimitive("snapshot"), sprite["identity_scope"])
                        assertNotNull(sprite["reference"])
                    }
                }
                println("Retained image metadata: ${JsonArray(images)}")
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

    @Test
    fun readsLowerHudAndNavigatesTipsThroughLiveWidgets() = runBlocking {
        val client = McpHttpClient(checkNotNull(getenv("FACTORIO_MCP_ACCEPTANCE_URL")).toKString())
        val pid = checkNotNull(getenv("FACTORIO_MCP_TEST_PID")).toKString().toInt()
        var attached = false
        var tipsOpen = false
        fun selector(vararg types: String, text: String? = null) = buildJsonObject {
            putJsonArray("path") {
                types.forEachIndexed { index, type ->
                    addJsonObject {
                        put("axis", "descendant")
                        putJsonObject("match") {
                            put("native_type", type)
                            if (index == types.lastIndex && text != null) put("text", text)
                        }
                    }
                }
            }
        }
        suspend fun read(type: String): List<JsonObject> {
            val result =
                client
                    .tool(
                        "ui_read",
                        buildJsonObject {
                            put("selector", selector(type))
                        },
                    )
                    .toolValue()
            assertFalse(result.getValue("truncated").jsonPrimitive.boolean)
            assertNull(result["tooltips_unavailable_reason"])
            assertNull(result["icon_names_unavailable_reason"])
            return result.getValue("nodes").jsonArray.map { it.jsonObject }
        }
        suspend fun click(parent: String, type: String, text: String? = null) {
            assertEquals(
                "completed",
                client
                    .tool(
                        "ui_action",
                        buildJsonObject {
                            put("action", "click")
                            put("selector", selector(parent, type, text = text))
                        },
                    )
                    .toolValue()
                    .getValue("dispatch")
                    .jsonPrimitive
                    .content,
            )
        }
        fun properties(nodes: List<JsonObject>) =
            nodes.mapNotNull { it["properties"] as? JsonObject }
        withTimeout(600_000) {
            try {
                client.initialize()
                client.tool("attach", buildJsonObject { put("pid", pid) }).toolValue()
                attached = true
                val quick = read("QuickBarGui")
                val slots = quick.filter { it["type"] == JsonPrimitive("QuickBarItemSlot") }
                assertTrue(slots.isNotEmpty())
                assertTrue(
                    properties(slots).any {
                        (it["prototype"] as? JsonObject)?.get("name") ==
                            JsonPrimitive("iron-plate") &&
                            (it["quality"] as? JsonObject)?.get("name") == JsonPrimitive("uncommon")
                    }
                )
                val shortcuts = read("ShortcutBarGui")
                assertTrue(
                    properties(shortcuts).any {
                        (it["prototype"] as? JsonObject)?.get("native_type") ==
                            JsonPrimitive("ShortcutPrototype") && it["icons"] is JsonObject
                    }
                )
                val equipment = read("ToolEquipGui")
                assertTrue(equipment.any { it["type"] == JsonPrimitive("InventoryGuiSlot") })
                assertTrue(
                    properties(equipment).any {
                        (it["tooltip"] as? JsonObject)?.get("status") == JsonPrimitive("stored")
                    }
                )
                println(
                    "Lower HUD: ${slots.size} quickbar slots, ${shortcuts.size} shortcut nodes, ${equipment.size} equipment nodes; raw identities and stored text present"
                )
                val hud = read("SideMenu")
                val structure = client.tool("ui_read", buildJsonObject {
                    put("selector", selector("SideMenu"))
                    putJsonArray("fields") {}
                }).toolValue()
                assertFalse(structure.getValue("truncated").jsonPrimitive.boolean)
                val structureNodes = structure.getValue("nodes").jsonArray.map { it.jsonObject }
                assertEquals(hud.size, structureNodes.size)
                assertTrue(structureNodes.all { "text" !in it && "properties" !in it && "enabled" in it })
                assertTrue(structureNodes.all { row -> (row["gui"] as? JsonObject)?.let {
                    "children" !in it && "properties" !in it && "read_status" !in it
                } ?: true })
                val iconOnly = client.tool("ui_read", buildJsonObject {
                    put("selector", selector("SideMenu"))
                    putJsonArray("fields") { add("properties.icons") }
                }).toolValue().getValue("nodes").jsonArray.map { it.jsonObject }
                assertTrue(iconOnly.all { "text" !in it &&
                    (it["properties"] as? JsonObject)?.keys?.all { key -> key == "icons" } != false })
                fun registeredNames(nodes: List<JsonObject>) = properties(nodes).mapNotNull { it["icons"] as? JsonObject }
                    .map { icons -> listOf("normal", "hovered", "disabled").map { key ->
                        (icons[key] as? JsonObject)?.get("name") ?: JsonNull
                    } }
                assertEquals(registeredNames(hud), registeredNames(iconOnly))
                val tips =
                    hud.single {
                        (((it["properties"] as? JsonObject)?.get("icons") as? JsonObject)?.get(
                                "normal"
                            ) as? JsonObject)
                            ?.get("name") == JsonPrimitive("side_menu_tutorials_icon")
                    }
                click("SideMenu", tips.getValue("type").jsonPrimitive.content)
                tipsOpen = true
                val directory = read("TipsAndTricksGui")
                val entry =
                    directory.first {
                        it["type"] == JsonPrimitive("IconButton") &&
                            it["enabled"] == JsonPrimitive(true) &&
                            it["visible"] == JsonPrimitive(true) &&
                            !it["text"]?.jsonPrimitive?.contentOrNull.isNullOrEmpty()
                    }
                val title = entry.getValue("text").jsonPrimitive.content
                click("TipsAndTricksGui", "IconButton", title)
                val content = read("TipsAndTricksGui")
                val paragraphs =
                    content
                        .filter { it["type"] == JsonPrimitive("LabelWithHoverableRichText") }
                        .mapNotNull { it["text"]?.jsonPrimitive?.contentOrNull }
                        .filter { it.isNotEmpty() }
                assertTrue(
                    paragraphs.isNotEmpty(),
                    "The selected tip must expose its game-provided body text",
                )
                assertTrue(
                    properties(content).any {
                        ((it["icons"] as? JsonObject)?.get("normal") as? JsonObject)?.get("name") ==
                            JsonPrimitive("close")
                    }
                )
                println(
                    "Tip directory entries=${directory.count { it["type"] == JsonPrimitive("IconButton") }}, selected=$title, body=${JsonArray(paragraphs.map(::JsonPrimitive))}"
                )
                click("TipsAndTricksGui", "CloseButton")
                tipsOpen = false
                assertTrue(read("SideMenu").any { it["type"] == tips["type"] })
            } finally {
                withContext(NonCancellable) {
                    try {
                        if (tipsOpen) click("TipsAndTricksGui", "CloseButton")
                    } finally {
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

    @Test
    fun readsRegisteredHudIconsAndStoredTooltipsWithoutHovering() = runBlocking {
        val client = McpHttpClient(checkNotNull(getenv("FACTORIO_MCP_ACCEPTANCE_URL")).toKString())
        val pid = checkNotNull(getenv("FACTORIO_MCP_TEST_PID")).toKString().toInt()
        var attached = false
        withTimeout(600_000) {
            try {
                client.initialize()
                client.tool("attach", buildJsonObject { put("pid", pid) }).toolValue()
                attached = true
                val request = buildJsonObject {
                    putJsonObject("selector") {
                        putJsonArray("path") {
                            add(
                                buildJsonObject {
                                    put("axis", "descendant")
                                    putJsonObject("match") { put("native_type", "SideMenu") }
                                }
                            )
                        }
                    }
                }
                var expected: List<JsonElement>? = null
                repeat(5) {
                    val snapshot = client.tool("ui_read", request).toolValue()
                    assertFalse(snapshot.getValue("truncated").jsonPrimitive.boolean)
                    assertNull(snapshot["icon_names_unavailable_reason"])
                    assertNull(snapshot["tooltips_unavailable_reason"])
                    assertWidgetIconReferences(snapshot)
                    val properties =
                        snapshot.getValue("nodes").jsonArray.mapNotNull {
                            it.jsonObject["properties"] as? JsonObject
                        }
                    val named =
                        properties.filter {
                            val icons = it["icons"] as? JsonObject
                            val normal = icons?.get("normal") as? JsonObject
                            normal
                                ?.get("name")
                                ?.jsonPrimitive
                                ?.contentOrNull
                                ?.startsWith("side_menu_") == true
                        }
                    assertTrue(named.size >= 5, "Expected registered icons in the ordinary HUD")
                    val pairs =
                        named.map { property ->
                            val name =
                                property
                                    .getValue("icons")
                                    .jsonObject
                                    .getValue("normal")
                                    .jsonObject
                                    .getValue("name")
                            val tooltip = property.getValue("tooltip").jsonObject
                            assertEquals("stored", tooltip.getValue("status").jsonPrimitive.content)
                            assertTrue(
                                tooltip.getValue("title").jsonPrimitive.content.isNotEmpty() ||
                                    tooltip.getValue("text").jsonPrimitive.content.isNotEmpty()
                            )
                            assertFalse(tooltip["title_truncated"] == JsonPrimitive(true))
                            assertFalse(tooltip["text_truncated"] == JsonPrimitive(true))
                            buildJsonObject {
                                put("name", name)
                                put("tooltip", tooltip)
                            }
                        }
                    if (expected == null) expected = pairs else assertEquals(expected, pairs)
                    if (it == 0) {
                        println("Observed HUD resources: ${JsonArray(pairs)}")
                        val entry = snapshot.getValue("nodes").jsonArray.map { it.jsonObject }.first { node ->
                            node["properties"]?.jsonObject?.get("icons")?.jsonObject?.get("normal")?.jsonObject?.get("name") ==
                                JsonPrimitive("side_menu_tutorials_icon")
                        }
                        val tooltip = entry.getValue("properties").jsonObject.getValue("tooltip").jsonObject
                        val relativePath = uiSelectorForNode(snapshot, entry.getValue("id").jsonPrimitive.int)
                            .getValue("path").jsonArray.drop(1)
                        val entrySelector = buildJsonObject {
                            put("path", JsonArray(request.getValue("selector").jsonObject.getValue("path").jsonArray + relativePath))
                        }
                        for ((source, field) in listOf("tooltip_title" to "title", "tooltip_text" to "text")) {
                            val original = tooltip.getValue(field).jsonPrimitive.content
                            val prefix = original.indexOf(' ').takeIf { index -> index >= 0 }?.let { end -> original.substring(0, end + 1) } ?: original
                            val sliced = client.tool("ui_read", buildJsonObject {
                                put("selector", entrySelector)
                                putJsonObject("collection") {
                                    put("source", source)
                                    put("limit", prefix.encodeToByteArray().size.coerceAtLeast(1))
                                }
                            }).toolValue().getValue("collection").jsonObject
                            assertEquals(prefix, sliced.getValue("value").jsonPrimitive.content)
                            assertEquals(original.encodeToByteArray().size, sliced.getValue("total").jsonPrimitive.int)
                        }
                    }
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
