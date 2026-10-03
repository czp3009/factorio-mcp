@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.acceptance

import com.hiczp.factorio.mcp.*
import kotlin.test.*
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

/** Requires the UI acceptance scenario on an isolated local server and its non-admin client. */
class UiFlowAcceptanceTest {
    @Test
    fun widgetEventsReachServerAndSurviveRecreation() = runBlocking {
        fun environment(name: String) = getenv(name)?.toKString()?.takeIf { it.isNotBlank() }
        val serverLog = environment("FACTORIO_MCP_UI_SERVER_LOG") ?: return@runBlocking
        val clientLog = checkNotNull(environment("FACTORIO_MCP_UI_CLIENT_LOG"))
        val url = checkNotNull(environment("FACTORIO_MCP_ACCEPTANCE_URL"))
        val pid = checkNotNull(environment("FACTORIO_MCP_TEST_PID")).toInt()
        fun read(path: String) =
            SystemFileSystem.source(Path(path)).buffered().use { it.readString() }

        fun records(log: String) =
            log.lineSequence()
                .filter { "UI_ACTION_ACCEPTANCE " in it }
                .map {
                    Json.parseToJsonElement(it.substringAfter("UI_ACTION_ACCEPTANCE ")).jsonObject
                }
                .toList()
        withTimeout(90_000) {
            val client = McpHttpClient(url)
            try {
                client.initialize()
                client.tool("attach", buildJsonObject { put("pid", pid) }).toolValue()
                assertGuiModifiersReleased(pid.toUInt())
                val initialCount = records(read(serverLog)).size
                suspend fun click(text: String, type: String? = null, modified: Boolean = false) {
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
                            },
                        )
                        .toolValue()
                }

                suspend fun replace(before: String, after: String) {
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
                }

                suspend fun waitFor(predicate: (List<JsonObject>) -> Boolean): List<JsonObject> {
                    while (true) {
                        val events = records(read(serverLog)).drop(initialCount)
                        if (predicate(events) && records(read(clientLog)).containsAll(events))
                            return events
                        delay(100)
                    }
                }

                val observedCheckStates = mutableMapOf<Boolean, Int>()
                suspend fun assertProperties(checked: Boolean) {
                    val snapshot = client.tool("ui_read").toolValue()
                    val fullBudgetTarget =
                        snapshot
                            .getValue("nodes")
                            .jsonArray
                            .map { it.jsonObject }
                            .single {
                                it["type"]?.jsonPrimitive?.content == "agui::DropDown" &&
                                    it["text"]?.jsonPrimitive?.content == "MCP budget target first"
                            }
                            .getValue("properties")
                            .jsonObject
                    assertEquals(3, fullBudgetTarget.getValue("options_total").jsonPrimitive.int)
                    assertTrue(fullBudgetTarget.getValue("options_truncated").jsonPrimitive.boolean)
                    assertTrue(fullBudgetTarget.getValue("options").jsonArray.isEmpty())
                    val targeted =
                        client
                            .tool(
                                "ui_read",
                                buildJsonObject {
                                    put(
                                        "selector",
                                        uiSelector("MCP resource target", "agui::Window"),
                                    )
                                },
                            )
                            .toolValue()
                    val targetedNodes = targeted.getValue("nodes").jsonArray.map { it.jsonObject }
                    val targetedOptions =
                        targetedNodes
                            .single { it["type"]?.jsonPrimitive?.content == "agui::DropDown" }
                            .getValue("properties")
                            .jsonObject
                    assertFalse(targetedOptions.getValue("options_truncated").jsonPrimitive.boolean)
                    assertEquals(
                        listOf(
                            "MCP budget target first",
                            "MCP budget target second",
                            "MCP budget target third",
                        ),
                        targetedOptions.getValue("options").jsonArray.map {
                            it.jsonObject.getValue("text").jsonPrimitive.content
                        },
                    )
                    assertTrue(
                        targetedNodes.any {
                            it["matched"]?.jsonPrimitive?.boolean == true &&
                                it["visible"]?.jsonPrimitive?.boolean == false
                        }
                    )
                    assertTrue(
                        targetedNodes.any {
                            it["type"]?.jsonPrimitive?.content == "agui::DropDown" &&
                                it["visible"]?.jsonPrimitive?.boolean == true
                        }
                    )
                    assertWidgetIconReferences(targeted)
                    assertWidgetIconReferences(snapshot)
                    val fixture =
                        client
                            .tool(
                                "ui_read",
                                buildJsonObject {
                                    put(
                                        "selector",
                                        uiSelector("MCP action fixture", "agui::Window"),
                                    )
                                },
                            )
                            .toolValue()
                    val icon =
                        fixture
                            .getValue("nodes")
                            .jsonArray
                            .filter {
                                it.jsonObject["type"] == JsonPrimitive("IconButtonWithNumber")
                            }
                            .mapNotNull {
                                (it.jsonObject["properties"] as? JsonObject)?.get("icons")
                                    as? JsonObject
                            }
                            .single()
                    assertNotEquals(JsonNull, icon.getValue("normal"))
                    assertNotEquals(JsonNull, icon.getValue("hovered"))
                    assertNotEquals(icon.getValue("normal"), icon.getValue("hovered"))
                    assertEquals(JsonNull, icon.getValue("disabled"))
                    val configured =
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
                                            add(buildJsonObject { put("index", "mcp_actions") })
                                            add(buildJsonObject { put("index", "sprite_fixture") })
                                        }
                                    }
                                    putJsonArray("fields") {
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
                    assertTrue(configured.getValue("read_status").jsonObject.isEmpty())
                    assertEquals(
                        buildJsonObject {
                            put("sprite", "item/iron-plate")
                            put("hovered_sprite", "item/copper-plate")
                            put("clicked_sprite", "item/steel-plate")
                        },
                        configured.getValue("attributes"),
                    )
                    assertNull(snapshot["progress_unavailable_reason"])
                    val progress =
                        snapshot.getValue("nodes").jsonArray.mapNotNull {
                            (it.jsonObject["properties"] as? JsonObject)?.get("progress")
                                as? JsonObject
                        }
                    assertTrue(progress.isNotEmpty())
                    progress.forEach {
                        assertTrue(it.getValue("value").jsonPrimitive.double.isFinite())
                        assertFalse(
                            it.getValue("direction").jsonPrimitive.content.startsWith("unknown_")
                        )
                        assertNotNull(it.getValue("has_text").jsonPrimitive.booleanOrNull)
                    }
                    assertNull(snapshot["properties_unavailable_reason"])
                    val nodes = snapshot.getValue("nodes").jsonArray.map { it.jsonObject }
                    fun properties(text: String) =
                        nodes
                            .single { it["text"]?.jsonPrimitive?.content == text }
                            .getValue("properties")
                            .jsonObject
                    val checkState = properties("MCP checkbox")["check_state"]!!.jsonPrimitive.int
                    observedCheckStates[checked]?.let { assertEquals(it, checkState) }
                    observedCheckStates[!checked]?.let { assertNotEquals(it, checkState) }
                    observedCheckStates[checked] = checkState
                    assertEquals(
                        checked,
                        properties("MCP toggle")["toggled"]!!.jsonPrimitive.boolean,
                    )
                    val dropdown = properties("MCP first")
                    assertEquals(0, dropdown["selected_index"]!!.jsonPrimitive.int)
                    assertEquals(0, dropdown["index_base"]!!.jsonPrimitive.int)
                    assertNull(dropdown["options_unavailable_reason"])
                    assertEquals(3, dropdown["options_total"]!!.jsonPrimitive.int)
                    assertFalse(dropdown["options_truncated"]!!.jsonPrimitive.boolean)
                    val options = dropdown.getValue("options").jsonArray.map { it.jsonObject }
                    assertEquals(
                        listOf(0, 1, 2),
                        options.map { it.getValue("index").jsonPrimitive.int },
                    )
                    assertEquals(
                        listOf("MCP first", "MCP second", "MCP third"),
                        options.map { it.getValue("text").jsonPrimitive.content },
                    )
                    val slider =
                        nodes
                            .single { it["type"]?.jsonPrimitive?.content == "agui::Slider" }
                            .getValue("properties")
                            .jsonObject
                    assertEquals(50.0, slider["value"]!!.jsonPrimitive.double)
                    assertEquals(0.0, slider["minimum"]!!.jsonPrimitive.double)
                    assertEquals(100.0, slider["maximum"]!!.jsonPrimitive.double)
                }
                click("MCP recreate")
                waitFor { events -> events.any { it["kind"]?.jsonPrimitive?.content == "created" } }
                assertProperties(false)
                suspend fun sliderClick(x: Double) {
                    client
                        .tool(
                            "ui_action",
                            buildJsonObject {
                                put("action", "click")
                                put("selector", uiSelector(null, "agui::Slider"))
                                putJsonObject("position") {
                                    put("x", x)
                                    put("y", 0.5)
                                }
                            },
                        )
                        .toolValue()
                }
                sliderClick(0.9)
                assertUiCaptureReleased(pid.toUInt())
                waitFor { current ->
                    current.any { event ->
                        event["kind"]?.jsonPrimitive?.content == "checkpoint" &&
                            (event["data"]?.jsonObject?.get("slider")?.jsonPrimitive?.double
                                ?: 0.0) >= 80.0
                    }
                }
                sliderClick(0.5)
                click("MCP option 1", "agui::DropDown")
                // The last option exists in the live tree even outside the dropdown's viewport.
                val optionSnapshot = client.tool("ui_read").toolValue()
                assertNull(optionSnapshot["visibility_unavailable_reason"])
                val lastOption =
                    optionSnapshot
                        .getValue("nodes")
                        .jsonArray
                        .map { it.jsonObject }
                        .single {
                            it["type"]?.jsonPrimitive?.content == "agui::TextButton" &&
                                it["text"]?.jsonPrimitive?.content == "MCP option 80"
                        }
                assertTrue(lastOption.getValue("visible").jsonPrimitive.boolean)
                click("MCP option 80", "agui::TextButton")
                waitFor { events ->
                    events.any { event ->
                        val data = event["data"] as? JsonObject
                        event["kind"]?.jsonPrimitive?.content == "selection" &&
                            data?.get("name")?.jsonPrimitive?.content == "long_selection" &&
                            data["selected_index"]?.jsonPrimitive?.int == 80
                    }
                }
                val selectedDropdown =
                    client
                        .tool("ui_read")
                        .toolValue()
                        .getValue("nodes")
                        .jsonArray
                        .map { it.jsonObject }
                        .single {
                            it["type"]?.jsonPrimitive?.content == "agui::DropDown" &&
                                it["text"]?.jsonPrimitive?.content == "MCP option 80"
                        }
                        .getValue("properties")
                        .jsonObject
                assertEquals(79, selectedDropdown["selected_index"]!!.jsonPrimitive.int)
                assertEquals(80, selectedDropdown["options_total"]!!.jsonPrimitive.int)
                assertTrue(selectedDropdown["options_truncated"]!!.jsonPrimitive.boolean)
                assertEquals(64, selectedDropdown["options"]!!.jsonArray.size)
                assertGuiModifiersReleased(pid.toUInt())
                client
                    .tool(
                        "ui_action",
                        buildJsonObject {
                            put("action", "press_key")
                            put("selector", uiSelector("MCP original"))
                            put("key", "A")
                            putJsonArray("modifiers") { add("control") }
                        },
                    )
                    .toolValue()
                assertGuiModifiersReleased(pid.toUInt())
                client
                    .tool(
                        "ui_action",
                        buildJsonObject {
                            put("action", "press_key")
                            put("key", "BACKSPACE")
                        },
                    )
                    .toolValue()
                waitFor { events ->
                    events.any { event ->
                        event["kind"]?.jsonPrimitive?.content == "checkpoint" &&
                            (event["data"] as? JsonObject)?.get("text")?.jsonPrimitive?.content ==
                                ""
                    }
                }
                val emptyField = buildJsonObject {
                    putJsonArray("path") {
                        add(
                            buildJsonObject {
                                put("axis", "descendant")
                                putJsonObject("match") {
                                    put("native_type", "agui::Window")
                                    put("text", "MCP action fixture")
                                }
                            }
                        )
                        add(
                            buildJsonObject {
                                put("axis", "descendant")
                                putJsonObject("match") {
                                    put("native_type", "agui::TextField")
                                    put("text", "")
                                }
                            }
                        )
                    }
                }
                client
                    .tool(
                        "ui_action",
                        buildJsonObject {
                            put("action", "set_text")
                            put("selector", emptyField)
                            put("text", "MCP original")
                        },
                    )
                    .toolValue()
                waitFor { events ->
                    events
                        .lastOrNull { it["kind"]?.jsonPrimitive?.content == "checkpoint" }
                        ?.get("data")
                        ?.jsonObject
                        ?.get("text")
                        ?.jsonPrimitive
                        ?.content == "MCP original"
                }
                click("MCP normal", modified = true)
                click("MCP checkbox", "agui::CheckBox")
                click("MCP toggle")
                click("MCP offscreen")
                replace("MCP original", "MCP 中文🚀")
                replace("123", "a9b8")
                replace("MCP read-only", "Must remain read-only")
                val rejected =
                    client.tool(
                        "ui_action",
                        buildJsonObject {
                            put("action", "click")
                            put("selector", uiSelector("MCP disabled"))
                        },
                    )
                assertTrue(rejected.getValue("isError").jsonPrimitive.boolean)
                val events = waitFor { events ->
                    events.any { event ->
                        val data = event["data"] as? JsonObject
                        event["kind"]?.jsonPrimitive?.content == "checkpoint" &&
                            data?.get("text")?.jsonPrimitive?.content == "MCP 中文🚀" &&
                            data["numeric"]?.jsonPrimitive?.content == "98" &&
                            data["readonly"]?.jsonPrimitive?.content == "MCP read-only" &&
                            data["checked"]?.jsonPrimitive?.boolean == true &&
                            data["toggle"]?.jsonPrimitive?.boolean == true
                    }
                }
                assertProperties(true)
                assertTrue(
                    events.any { event ->
                        val data = event["data"] as? JsonObject
                        event["kind"]?.jsonPrimitive?.content == "click" &&
                            data?.get("name")?.jsonPrimitive?.content == "normal" &&
                            data["control"]?.jsonPrimitive?.boolean == true &&
                            data["shift"]?.jsonPrimitive?.boolean == true
                    }
                )
                assertTrue(
                    events.any {
                        it["data"]?.jsonObject?.get("name")?.jsonPrimitive?.content == "offscreen"
                    }
                )
                assertTrue(
                    events
                        .filter { "admin" in it }
                        .all { !it.getValue("admin").jsonPrimitive.boolean }
                )
                val checkpointTick =
                    events
                        .last { it["kind"]?.jsonPrimitive?.content == "checkpoint" }
                        .getValue("tick")
                        .jsonPrimitive
                        .long
                while ("UI_ACTION_FULL_CRC $checkpointTick" !in read(clientLog)) delay(100)
                val clientText = read(clientLog)
                val clientEvents = records(clientText)
                assertTrue(clientEvents.containsAll(events))
                assertFalse(clientText.contains("desynchron", ignoreCase = true))
                client.tool("screenshot").toolValue()
                click("MCP recreate")
                waitFor { current ->
                    current.count { it["kind"]?.jsonPrimitive?.content == "created" } >= 2
                }
                click("MCP normal")
                val completed = waitFor { current ->
                    current.any { event ->
                        val data = event["data"] as? JsonObject
                        event["kind"]?.jsonPrimitive?.content == "click" &&
                            data?.get("name")?.jsonPrimitive?.content == "normal" &&
                            data["control"]?.jsonPrimitive?.boolean == false
                    }
                }
                val lastActionTick = completed.last().getValue("tick").jsonPrimitive.long
                suspend fun key(name: String) {
                    client
                        .tool(
                            "ui_action",
                            buildJsonObject {
                                put("action", "press_key")
                                put("key", name)
                            },
                        )
                        .toolValue()
                }

                suspend fun cursor() =
                    client
                        .tool(
                            "world_query",
                            buildJsonObject {
                                putJsonObject("selection") { put("kind", "player") }
                                putJsonArray("fields") { add("cursor_stack") }
                            },
                        )
                        .toolValue()
                        .getValue("objects")
                        .jsonArray
                        .single()
                        .jsonObject
                        .getValue("attributes")
                        .jsonObject
                        .getValue("cursor_stack")
                        .jsonObject
                assertFalse(cursor().getValue("valid_for_read").jsonPrimitive.boolean)
                key("E")
                try {
                    val inventorySnapshot = client.tool("ui_read").toolValue()
                    assertNull(inventorySnapshot["element_unavailable_reason"])
                    assertNull(inventorySnapshot["quality_condition_unavailable_reason"])
                    val inventory = inventorySnapshot.getValue("nodes").jsonArray
                    val filterProperties =
                        inventory
                            .map { it.jsonObject }
                            .filter { it["type"]?.jsonPrimitive?.content == "InventoryGuiSlot" }
                            .mapNotNull { it["properties"] as? JsonObject }
                            .filter {
                                it["prototype"]?.jsonObject?.get("name")?.jsonPrimitive?.content ==
                                    "copper-ore"
                            }
                    assertEquals(6, filterProperties.size)
                    assertEquals(
                        setOf(">", "<", "=", "≥", "≤", "≠"),
                        filterProperties
                            .map {
                                it.getValue("quality_condition")
                                    .jsonObject
                                    .getValue("comparison")
                                    .jsonPrimitive
                                    .content
                            }
                            .toSet(),
                    )
                    assertTrue(
                        filterProperties.all {
                            it.getValue("quality_condition")
                                .jsonObject
                                .getValue("quality_name")
                                .jsonPrimitive
                                .content == "rare"
                        }
                    )
                    filterProperties.forEach {
                        val condition = it.getValue("quality_condition").jsonObject
                        assertEquals(
                            "present",
                            condition.getValue("quality_lookup").jsonPrimitive.content,
                        )
                        val observedQuality = it.getValue("quality").jsonObject.getValue("name")
                        if (condition.getValue("comparison").jsonPrimitive.content == "=")
                            assertEquals(JsonPrimitive("rare"), observedQuality)
                        else assertEquals(JsonNull, observedQuality)
                    }
                    assertEquals(
                        1,
                        filterProperties
                            .map {
                                it.getValue("quality_condition")
                                    .jsonObject
                                    .getValue("quality_index")
                            }
                            .toSet()
                            .size,
                    )
                    val filterFixture =
                        records(read(serverLog))
                            .single { it["kind"]?.jsonPrimitive?.content == "condition_filters" }
                            .getValue("data")
                            .jsonArray
                    assertEquals(
                        setOf(">", "<", "=", "≥", "≤", "≠"),
                        filterFixture
                            .map { it.jsonObject.getValue("comparator").jsonPrimitive.content }
                            .toSet(),
                    )
                    val itemFixture =
                        records(read(serverLog))
                            .single { it["kind"]?.jsonPrimitive?.content == "item_properties" }
                            .getValue("data")
                            .jsonObject

                    fun elements(name: String) =
                        inventory
                            .map { it.jsonObject }
                            .filter { it["type"]?.jsonPrimitive?.content == "InventoryGuiSlot" }
                            .mapNotNull { it["properties"] as? JsonObject }
                            .filter {
                                it["prototype"]?.jsonObject?.get("name")?.jsonPrimitive?.content ==
                                    name
                            }
                            .map { it.getValue("element") }

                    fun items(name: String) =
                        elements(name).mapNotNull { it.jsonObject["item"] as? JsonObject }
                    // The weapon inventory can show another stack of the same item. Verify
                    // the modified fixture's observed value without requiring name uniqueness.
                    assertTrue(
                        items("stone-furnace").any {
                            it.getValue("health").jsonPrimitive.double ==
                                itemFixture.getValue("health").jsonPrimitive.double
                        }
                    )
                    assertTrue(
                        items("repair-pack").any {
                            it.getValue("durability_left").jsonPrimitive.double ==
                                itemFixture.getValue("durability").jsonPrimitive.double
                        }
                    )
                    assertTrue(
                        items("firearm-magazine").any {
                            it.getValue("magazine_left").jsonPrimitive.double ==
                                itemFixture.getValue("ammo").jsonPrimitive.double
                        }
                    )
                    // Stack providers distinguish a missing stack from an unallocated Item.
                    assertEquals(JsonNull, elements("coal").single())
                    assertTrue(elements("iron-plate").all { it.jsonObject["item"] == JsonNull })
                    val ironQualities =
                        inventory
                            .map { it.jsonObject }
                            .filter {
                                it["type"]?.jsonPrimitive?.content == "InventoryGuiSlot" &&
                                    (it["properties"] as? JsonObject)
                                        ?.get("prototype")
                                        ?.jsonObject
                                        ?.get("name")
                                        ?.jsonPrimitive
                                        ?.content == "iron-plate"
                            }
                            .map {
                                it.getValue("properties")
                                    .jsonObject
                                    .getValue("quality")
                                    .jsonObject
                                    .getValue("name")
                                    .jsonPrimitive
                                    .content
                            }
                            .toSet()
                    assertTrue(ironQualities.containsAll(setOf("normal", "uncommon")))
                    val identitySelector = buildJsonObject {
                        putJsonArray("path") {
                            add(
                                buildJsonObject {
                                    put("axis", "descendant")
                                    putJsonObject("match") {
                                        put("native_type", "InventoryGuiSlot")
                                        putJsonObject("prototype") { put("name", "iron-plate") }
                                    }
                                    put("position", 1)
                                }
                            )
                        }
                    }
                    // Selectors use the same provider identity as the snapshot, not inventory
                    // indices.
                    val identified =
                        client
                            .tool("ui_read", buildJsonObject { put("selector", identitySelector) })
                            .toolValue()
                    assertNull(identified["slot_identity_unavailable_reason"])
                    assertNull(identified["number_unavailable_reason"])
                    val slotProperties =
                        identified
                            .getValue("nodes")
                            .jsonArray
                            .first {
                                it.jsonObject["properties"]
                                    ?.jsonObject
                                    ?.get("prototype")
                                    ?.jsonObject
                                    ?.get("name")
                                    ?.jsonPrimitive
                                    ?.content == "iron-plate"
                            }
                            .jsonObject
                            .getValue("properties")
                            .jsonObject
                    val slotNumber = slotProperties.getValue("number").jsonObject
                    val observedQuality =
                        slotProperties
                            .getValue("quality")
                            .jsonObject
                            .getValue("name")
                            .jsonPrimitive
                            .content
                    assertTrue(slotNumber.getValue("draw_requested").jsonPrimitive.boolean)
                    val observedCount = slotNumber.getValue("value").jsonPrimitive.double
                    assertTrue(observedCount > 0)
                    assertTrue(
                        identified.getValue("nodes").jsonArray.any {
                            it.jsonObject["properties"]
                                ?.jsonObject
                                ?.get("prototype")
                                ?.jsonObject
                                ?.get("name")
                                ?.jsonPrimitive
                                ?.content == "iron-plate"
                        }
                    )
                    client
                        .tool(
                            "ui_action",
                            buildJsonObject {
                                put("action", "click")
                                put("selector", identitySelector)
                            },
                        )
                        .toolValue()
                    while (!cursor().getValue("valid_for_read").jsonPrimitive.boolean) delay(20)
                    assertEquals("iron-plate", cursor().getValue("name").jsonPrimitive.content)
                    assertEquals(observedCount, cursor().getValue("count").jsonPrimitive.double)
                    assertEquals(
                        observedQuality,
                        cursor()
                            .getValue("quality")
                            .jsonObject
                            .getValue("name")
                            .jsonPrimitive
                            .content,
                    )
                } finally {
                    key("Q")
                    while (cursor().getValue("valid_for_read").jsonPrimitive.boolean) delay(20)
                    key("E")
                }
                val afterIdentityTick =
                    client
                        .tool(
                            "world_query",
                            buildJsonObject { putJsonObject("selection") { put("kind", "player") } },
                        )
                        .toolValue()
                        .getValue("tick")
                        .jsonPrimitive
                        .long
                waitFor { current ->
                    current.any {
                        it["kind"]?.jsonPrimitive?.content == "checkpoint" &&
                            it.getValue("tick").jsonPrimitive.long >
                                maxOf(lastActionTick, afterIdentityTick) + 300
                    }
                }
                assertFalse(read(clientLog).contains("desynchron", ignoreCase = true))
            } finally {
                client.close()
            }
        }
    }
}
