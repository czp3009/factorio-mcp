@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.acceptance

import com.hiczp.factorio.mcp.McpHttpClient
import com.hiczp.factorio.mcp.toolValue
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
import kotlin.math.roundToInt
import kotlin.test.*

/** Native train UI uses ordinary world input and widget dispatch; no product train adapter. */
class TrainUiAcceptanceTest {
    @Test
    fun scheduleEditsSurviveRemoteViewAndReachTheAuthoritativeServer() = runBlocking {
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

        fun step(
            type: String,
            text: String? = null,
            position: Int? = null,
            axis: String = "descendant",
        ) = buildJsonObject {
            put("axis", axis)
            putJsonObject("match") {
                put("native_type", type)
                text?.let { put("text", it) }
            }
            position?.let { put("position", it) }
        }

        fun selector(vararg steps: JsonObject) = buildJsonObject {
            put("path", JsonArray(steps.toList()))
        }

        suspend fun ui(selection: JsonObject? = null): JsonObject =
            client
                .tool("ui_read", buildJsonObject { selection?.let { put("selector", it) } })
                .toolValue()
                .also { assertNull(it["switch_unavailable_reason"]) }

        fun JsonObject.nodes() = getValue("nodes").jsonArray.map { it.jsonObject }
        suspend fun waitUi(predicate: (List<JsonObject>) -> Boolean): JsonObject {
            while (true) {
                val value = ui()
                if (predicate(value.nodes())) return value
                delay(30)
            }
        }

        suspend fun click(selection: JsonObject) =
            client
                .tool(
                    "ui_action",
                    buildJsonObject {
                        put("action", "click")
                        put("selector", selection)
                    },
                )
                .toolValue()

        suspend fun clickText(text: String) = click(selector(step("agui::TextButton", text)))
        suspend fun key(key: String) =
            client
                .tool(
                    "ui_action",
                    buildJsonObject {
                        put("action", "press_key")
                        put("key", key)
                    },
                )
                .toolValue()

        suspend fun player() =
            client
                .tool(
                    "world_query",
                    buildJsonObject {
                        putJsonObject("selection") { put("kind", "player") }
                        putJsonArray("fields") {
                            add("controller_type")
                            add("position")
                            add("surface")
                        }
                    },
                )
                .toolValue()
                .getValue("objects")
                .jsonArray
                .single()
                .jsonObject
                .getValue("attributes")
                .jsonObject

        suspend fun waitPlayer(predicate: (JsonObject) -> Boolean): JsonObject {
            while (true) {
                val value = player()
                if (predicate(value)) return value
                delay(30)
            }
        }

        fun hasText(nodes: List<JsonObject>, text: String) =
            nodes.any { it["text"]?.jsonPrimitive?.content == text }

        val trainSwitch = selector(step("TrainGui"), step("agui::Switch"))
        suspend fun switchState() =
            ui(trainSwitch)
                .nodes()
                .single()
                .getValue("properties")
                .jsonObject
                .getValue("switch")
                .jsonObject

        suspend fun waitSwitch(state: String) {
            while (switchState().getValue("state").jsonPrimitive.content != state) delay(30)
        }
        withTimeout(120_000) {
            try {
                client.initialize()
                client.tool("attach", buildJsonObject { put("pid", pid) }).toolValue()
                val initial = player()
                val initialEventCount = records(serverLog).size
                suspend fun waitEvent(
                    kind: String,
                    afterTick: Long = -1,
                    predicate: (JsonObject) -> Boolean,
                ): JsonObject {
                    while (true) {
                        val event =
                            records(serverLog).drop(initialEventCount).lastOrNull {
                                it["kind"]?.jsonPrimitive?.content == kind &&
                                        it.getValue("tick").jsonPrimitive.long > afterTick &&
                                        predicate(it.getValue("data").jsonObject)
                            }
                        if (event != null && records(clientLog).contains(event)) return event
                        delay(50)
                    }
                }

                val scriptSwitch =
                    ui(selector(step("agui::Window", "MCP action fixture"), step("agui::Switch")))
                        .nodes()
                        .single()
                        .getValue("properties")
                        .jsonObject
                        .getValue("switch")
                        .jsonObject
                assertEquals("None", scriptSwitch.getValue("state").jsonPrimitive.content)
                assertTrue(scriptSwitch.getValue("allow_none").jsonPrimitive.boolean)
                clickText("MCP train fixture")
                waitPlayer {
                    it.getValue("surface").jsonObject.getValue("name").jsonPrimitive.content ==
                            "mcp-train-ui"
                }
                val train =
                    client
                        .tool(
                            "world_query",
                            buildJsonObject {
                                putJsonObject("selection") {
                                    put("kind", "entities")
                                    put("type", "locomotive")
                                    put("radius", 16)
                                    putJsonObject("position") {
                                        put("x", 0)
                                        put("y", 0)
                                    }
                                }
                                putJsonArray("fields") {
                                    add("position")
                                    add("unit_number")
                                }
                            },
                        )
                        .toolValue()
                        .getValue("objects")
                        .jsonArray
                        .single()
                        .jsonObject
                        .getValue("attributes")
                        .jsonObject
                val position = train.getValue("position").jsonObject
                val viewport =
                    client.tool("world_overview").toolValue().getValue("viewport").jsonObject
                val area = viewport.getValue("area").jsonObject
                fun pixel(axis: String, dimension: String): Int {
                    val low =
                        area.getValue("left_top").jsonObject.getValue(axis).jsonPrimitive.double
                    val high =
                        area.getValue("right_bottom").jsonObject.getValue(axis).jsonPrimitive.double
                    return ((position.getValue(axis).jsonPrimitive.double - low) / (high - low) *
                            viewport.getValue(dimension).jsonPrimitive.int)
                        .roundToInt()
                }

                val binding =
                    client
                        .tool(
                            "input_bindings",
                            buildJsonObject { putJsonArray("ids") { add("open-gui") } },
                        )
                        .toolValue()
                        .getValue("controls")
                        .jsonArray
                        .single()
                        .jsonObject
                        .getValue("bindings")
                        .jsonArray
                        .map { it.jsonObject }
                        .single {
                            it.getValue("slot").jsonPrimitive.content == "keyboard_mouse_primary"
                        }
                val controls =
                    binding.getValue("modifiers").jsonArray.map { modifier ->
                        buildJsonObject {
                            put("device", "keyboard")
                            put(
                                "key",
                                mapOf("control" to "LCTRL", "shift" to "LSHIFT", "alt" to "LALT")
                                    .getValue(modifier.jsonPrimitive.content),
                            )
                        }
                    } +
                            buildJsonObject {
                                val keyboard =
                                    binding.getValue("type").jsonPrimitive.content == "Keyboard"
                                put("device", if (keyboard) "keyboard" else "mouse")
                                put(if (keyboard) "key" else "button", binding.getValue("name"))
                            }
                client
                    .tool(
                        "input",
                        buildJsonObject {
                            putJsonArray("operations") {
                                addJsonObject {
                                    put("ticks", 2)
                                    putJsonArray("controls") {
                                        addJsonObject {
                                            put("device", "mouse")
                                            putJsonObject("position") {
                                                put("space", "viewport")
                                                put("x", pixel("x", "width"))
                                                put("y", pixel("y", "height"))
                                            }
                                        }
                                    }
                                }
                                addJsonObject { put("controls", JsonArray(controls)) }
                            }
                        },
                    )
                    .toolValue()
                waitUi { it.any { node -> node["type"]?.jsonPrimitive?.content == "TrainGui" } }
                waitEvent("opened_entity") { it["unit"] == train["unit_number"] }
                assertFalse(switchState().getValue("allow_none").jsonPrimitive.boolean)
                waitSwitch("Right")
                clickText("+ 添加停靠站")
                waitUi { hasText(it, "MCP Alpha") }
                clickText("MCP Alpha")
                waitUi { hasText(it, "+ 添加发车条件") }
                clickText("+ 添加发车条件")
                clickText("定时停靠")
                waitUi { hasText(it, "30 s") }
                clickText("30 s")
                val timeField = selector(step("NumberInputWindow"), step("agui::TextField", "30"))
                client
                    .tool(
                        "ui_action",
                        buildJsonObject {
                            put("action", "set_text")
                            put("selector", timeField)
                            put("text", "37")
                        },
                    )
                    .toolValue()
                click(selector(step("NumberInputWindow"), step("IconButton")))
                waitUi { hasText(it, "37 s") }
                clickText("+ 添加停靠站")
                clickText("MCP Beta")
                waitUi {
                    it.count { node ->
                        node["type"]?.jsonPrimitive?.content == "ScheduleStationGui"
                    } == 2
                }
                click(
                    selector(
                        step("ScheduleStationGui", position = 2),
                        step("agui::TextButton", "+ 添加发车条件"),
                    )
                )
                clickText("清空货物")
                waitUi { hasText(it, "清空车厢") }
                val schedule =
                    waitEvent("train_schedule") { data ->
                        val stops = data["schedule"]?.jsonObject?.get("records") as? JsonArray
                        stops?.size == 2 &&
                                stops[1].jsonObject["wait_conditions"] is JsonArray &&
                                stops[1]
                                    .jsonObject
                                    .getValue("wait_conditions")
                                    .jsonArray
                                    .isNotEmpty()
                    }
                        .getValue("data")
                        .jsonObject
                        .getValue("schedule")
                        .jsonObject
                        .getValue("records")
                        .jsonArray
                assertEquals(
                    listOf("MCP Alpha", "MCP Beta"),
                    schedule.map { it.jsonObject.getValue("station").jsonPrimitive.content },
                )
                val wait =
                    schedule[0].jsonObject.getValue("wait_conditions").jsonArray.single().jsonObject
                assertEquals("time", wait.getValue("type").jsonPrimitive.content)
                assertEquals(2220, wait.getValue("ticks").jsonPrimitive.int)
                assertEquals(
                    "empty",
                    schedule[1]
                        .jsonObject
                        .getValue("wait_conditions")
                        .jsonArray
                        .single()
                        .jsonObject
                        .getValue("type")
                        .jsonPrimitive
                        .content,
                )
                click(trainSwitch)
                waitSwitch("Left")
                val automatic =
                    waitEvent("train_checkpoint") {
                        !it.getValue("manual_mode").jsonPrimitive.boolean
                    }
                client.tool("screenshot").toolValue()
                val tick = automatic.getValue("tick").jsonPrimitive.long
                while (
                    !read(clientLog).lineSequence().any {
                        "UI_ACTION_FULL_CRC " in it &&
                                it.substringAfter("UI_ACTION_FULL_CRC ").trim().toLong() > tick
                    }
                ) delay(50)
                click(trainSwitch)
                waitSwitch("Right")
                waitEvent("train_checkpoint", tick) {
                    it.getValue("manual_mode").jsonPrimitive.boolean
                }
                for (remaining in listOf(2, 1)) {
                    click(
                        selector(
                            step("ScheduleStationGui", position = remaining),
                            step("agui::Frame", position = 1, axis = "child"),
                            step("agui::HorizontalFlow", position = 1, axis = "child"),
                            step("IconButton", position = 2, axis = "child"),
                        )
                    )
                    waitUi {
                        it.count { node ->
                            node["type"]?.jsonPrimitive?.content == "ScheduleStationGui"
                        } == remaining - 1
                    }
                }
                val cleared =
                    waitEvent("train_schedule", tick) {
                        it["schedule"] == null || it["schedule"] == JsonNull
                    }
                key("ESCAPE")
                waitUi { it.none { node -> node["type"]?.jsonPrimitive?.content == "TrainGui" } }
                waitPlayer { it["controller_type"] == initial["controller_type"] }
                clickText("MCP train fixture")
                waitPlayer {
                    it["surface"] == initial["surface"] && it["position"] == initial["position"]
                }
                val clearTick = cleared.getValue("tick").jsonPrimitive.long
                while (
                    listOf(serverLog, clientLog).any { path ->
                        !read(path).lineSequence().any {
                            "UI_ACTION_FULL_CRC " in it &&
                                    it.substringAfter("UI_ACTION_FULL_CRC ").trim().toLong() >
                                    clearTick + 300
                        }
                    }
                ) delay(50)
                assertFalse(read(clientLog).contains("desynchron", ignoreCase = true))
                assertFalse(read(serverLog).contains("desynchron", ignoreCase = true))
            } finally {
                client.close()
            }
        }
    }
}
