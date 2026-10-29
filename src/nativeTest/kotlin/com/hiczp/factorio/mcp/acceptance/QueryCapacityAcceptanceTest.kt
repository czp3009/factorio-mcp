@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.acceptance

import com.hiczp.factorio.mcp.McpHttpClient
import com.hiczp.factorio.mcp.toolValue
import com.hiczp.factorio.mcp.uiSelector
import kotlin.test.*
import kotlinx.cinterop.toKString
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import kotlinx.io.buffered
import kotlinx.io.readString
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.getenv

/** Explicitly requires the disposable query-capacity scenario and an operator-selected endpoint/PID. */
class QueryCapacityAcceptanceTest {
    @Test
    fun readsLargeSingleObservationsAndPagesStableCollections() = runBlocking {
        fun environment(name: String) = checkNotNull(getenv(name)) { "Select $name explicitly" }.toKString()
        val client = McpHttpClient(environment("FACTORIO_MCP_ACCEPTANCE_URL"))
        val pid = environment("FACTORIO_MCP_TEST_PID").toInt()
        suspend fun tool(name: String, args: String = "{}") =
            client.tool(name, Json.parseToJsonElement(args).jsonObject).toolValue()
        suspend fun query(args: String) = tool("world_query", args)
        fun JsonObject.rows() = getValue("objects").jsonArray
        var attached = false
        withTimeout(600_000) {
            try {
                client.initialize()
                tool("attach", """{"pid":$pid}""")
                attached = true
                val fixturePath = """[{"property":"gui"},{"property":"screen"},{"index":"factorio_mcp_query_capacity"}]"""
                val fixture = query("""{"selection":{"kind":"inspect","target":{"kind":"player"},"path":$fixturePath},"fields":["name"]}""")
                assertEquals("factorio_mcp_query_capacity", fixture.rows().single().jsonObject
                    .getValue("attributes").jsonObject.getValue("name").jsonPrimitive.content)

                val limited = tool("ui_read", """{"max_nodes":4096}""")
                assertTrue(limited.getValue("truncated").jsonPrimitive.boolean)
                val ui = tool("ui_read", """{"max_nodes":50000}""")
                val nodes = ui.getValue("nodes").jsonArray.map { it.jsonObject }
                assertTrue(nodes.size > 5000)
                assertFalse(ui.getValue("truncated").jsonPrimitive.boolean)
                val options = nodes.mapNotNull { it["properties"]?.jsonObject?.get("options")?.jsonArray }
                assertTrue(options.any { it.size == 1200 }, "Dropdown options were still capped")
                val selected = tool("ui_read", """{"max_nodes":50000,"selector":{"path":[{"axis":"descendant","match":{"text":"MCP bulk label 4999"}}]}}""")
                assertEquals(1, selected.getValue("nodes").jsonArray.count {
                    it.jsonObject["matched"]?.jsonPrimitive?.boolean == true
                })
                println("factorio-mcp capacity: UI nodes=${nodes.size}, largest options=${options.maxOf { it.size }}")

                val bindings = tool("input_bindings", """{"limit":100000}""")
                assertTrue(bindings.getValue("snapshot_complete").jsonPrimitive.boolean)
                val controls = bindings.getValue("controls").jsonArray
                assertEquals(bindings.getValue("registry_count").jsonPrimitive.int, controls.size)
                val bindingPage = tool("input_bindings", """{"offset":64,"limit":100000}""")
                assertEquals(controls.drop(64), bindingPage.getValue("controls").jsonArray.toList())

                val area = """{"left_top":{"x":-1,"y":-1},"right_bottom":{"x":81,"y":81}}"""
                val entities = query("""{"selection":{"kind":"entities","area":$area,"name":"wooden-chest"},"fields":["name","position"],"limit":10000}""")
                assertEquals(1600, entities.rows().size)
                assertFalse(entities.getValue("truncated").jsonPrimitive.boolean)
                val tiles = query("""{"selection":{"kind":"tiles","area":$area},"fields":["name"],"limit":100000}""")
                assertEquals(82 * 82, tiles.rows().size)
                val overview = tool("world_overview", """{"area":$area,"detail":"entities","name":"wooden-chest","entity_limit":10000}""")
                assertEquals(1600, overview.rows().size)
                val grid = tool("world_overview", """{"area":$area,"cell_size":4,"name":"wooden-chest","entity_limit":10000}""")
                assertEquals(441, grid.rows().size)
                assertFalse(grid.getValue("truncated").jsonPrimitive.boolean)

                val inventories = query("""{"selection":{"kind":"inventories"},"limit":10000}""")
                assertTrue(inventories.rows().isNotEmpty())
                val inventory = query("""{"selection":{"kind":"inventory"},"limit":10000}""")
                assertTrue(inventory.rows().size >= 2000)
                val inventoryPage = query("""{"selection":{"kind":"inventory"},"offset":512,"limit":10000}""")
                assertEquals(inventory.rows().drop(512), inventoryPage.rows().toList())

                for (kind in listOf("recipes", "technologies", "players")) {
                    val all = query("""{"selection":{"kind":"$kind"},"limit":10000}""")
                    assertFalse(all.getValue("truncated").jsonPrimitive.boolean)
                    val page = query("""{"selection":{"kind":"$kind"},"offset":64,"limit":10000}""")
                    assertEquals(all.rows().drop(64), page.rows().toList())
                    println("factorio-mcp capacity: $kind=${all.rows().size}")
                }
                for (type in listOf("item", "recipe", "entity", "fluid", "technology", "quality", "item_group", "item_subgroup")) {
                    val catalog = query("""{"selection":{"kind":"prototypes","type":"$type"},"fields":["name"],"limit":100000}""")
                    assertTrue(catalog.rows().isNotEmpty())
                    assertFalse(catalog.getValue("truncated").jsonPrimitive.boolean)
                    println("factorio-mcp capacity: prototypes/$type=${catalog.rows().size}")
                }

                val largeRequest = buildJsonObject {
                    putJsonObject("selection") {
                        put("kind", "prototypes")
                        put("type", "item")
                        putJsonArray("names") {
                            repeat(5000) { add("__factorio_mcp_missing_capacity_${it}_" + "x".repeat(80)) }
                        }
                    }
                    putJsonArray("fields") { add("name") }
                    put("limit", 10000)
                }.toString()
                assertTrue(largeRequest.encodeToByteArray().size > 262144)
                val absent = query(largeRequest)
                assertTrue(absent.rows().isEmpty())
                assertEquals(5000, absent.getValue("missing_names").jsonArray.size)

                val payloadPath = fixturePath.dropLast(1) + """,{"property":"tags"},{"index":"payload"}]"""
                val large = query("""{"selection":{"kind":"inspect","target":{"kind":"player"},"path":$payloadPath},"limit":10000}""")
                val payload = large.rows().single().jsonObject.getValue("value").jsonPrimitive.content
                assertEquals(17 * 1024 * 1024 + 7, payload.length)
                assertTrue(payload.all { it == 'x' })
                println("factorio-mcp capacity: payload_bytes=${payload.length}")
                val chat = tool("chat_read", """{"limit":10000}""")
                assertFalse(chat.getValue("snapshot_truncated").jsonPrimitive.boolean)
                assertFalse(chat.getValue("boundary_truncated").jsonPrimitive.boolean)
                val messages = chat.getValue("messages").jsonArray
                assertTrue(messages.isNotEmpty())
                assertEquals(chat.getValue("retained_counts").jsonArray.sumOf { it.jsonPrimitive.int }, messages.size)
                val pagedMessages = mutableListOf<JsonElement>()
                var offset: JsonElement = buildJsonObject {
                    put("tick", messages.first().jsonObject.getValue("tick"))
                    putJsonArray("counts") {
                        add(0)
                        add(0)
                    }
                }
                do {
                    val page = tool("chat_read", buildJsonObject {
                        put("limit", 16)
                        put("offset", offset)
                    }.toString())
                    pagedMessages.addAll(page.getValue("messages").jsonArray)
                    offset = page.getValue("next_offset")
                } while (page.getValue("has_more").jsonPrimitive.boolean)
                assertEquals(messages.toList(), pagedMessages)
                val drained = tool("chat_read", buildJsonObject {
                    put("limit", 10000)
                    put("offset", chat.getValue("next_offset"))
                }.toString())
                assertTrue(drained.getValue("messages").jsonArray.isEmpty())
                val marker = "MCP query capacity complete"
                tool("chat_send", buildJsonObject { put("text", marker) }.toString())
                val delivered = tool("chat_read", buildJsonObject {
                    put("offset", drained.getValue("next_offset"))
                    put("timeout", 30)
                }.toString())
                assertTrue(delivered.getValue("messages").jsonArray.any {
                    it.jsonObject.getValue("raw").jsonPrimitive.content.contains(marker)
                })
                println("factorio-mcp capacity: controls=${controls.size}, entities=${entities.rows().size}, tiles=${tiles.rows().size}, inventory=${inventory.rows().size}, chat=${messages.size}, payload_bytes=${payload.length}")
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
    fun verifiesMultiplayerMovementReleaseAndLaterFullCrc() = runBlocking {
        fun environment(name: String) = checkNotNull(getenv(name)) { "Select $name explicitly" }.toKString()
        val client = McpHttpClient(environment("FACTORIO_MCP_ACCEPTANCE_URL"))
        val pid = environment("FACTORIO_MCP_TEST_PID").toInt()
        val serverLog = environment("FACTORIO_MCP_UI_SERVER_LOG")
        val clientLog = environment("FACTORIO_MCP_UI_CLIENT_LOG")
        fun read(path: String) = SystemFileSystem.source(Path(path)).buffered().use { it.readString() }
        fun positions(path: String) = read(path).lineSequence().mapNotNull {
            it.substringAfter("QUERY_CAPACITY_POSITION ", "").takeIf(String::isNotBlank)
                ?.let { value -> Json.parseToJsonElement(value).jsonObject }
        }.toList()
        fun crc(path: String) = read(path).lineSequence().mapNotNull {
            it.substringAfter("query capacity full CRC requested at tick ", "").toLongOrNull()
        }.lastOrNull() ?: -1
        suspend fun nextCrc() {
            val before = maxOf(crc(serverLog), crc(clientLog))
            while (minOf(crc(serverLog), crc(clientLog)) <= before) delay(100)
            assertFalse(read(serverLog).contains("desynchron", ignoreCase = true))
            assertFalse(read(clientLog).contains("desynchron", ignoreCase = true))
        }
        suspend fun tool(name: String, args: JsonObject = JsonObject(emptyMap())) = client.tool(name, args).toolValue()
        suspend fun position() = tool("world_query", buildJsonObject {
            putJsonObject("selection") { put("kind", "player") }
            putJsonArray("fields") { add("position") }
        }).getValue("player").jsonObject.getValue("position").jsonObject
        var attached = false
        withTimeout(600_000) {
            try {
                client.initialize()
                tool("attach", buildJsonObject { put("pid", pid) })
                attached = true
                val fixture = tool("world_query", Json.parseToJsonElement("""{"selection":{"kind":"inspect","target":{"kind":"player"},"path":[{"property":"gui"},{"property":"screen"},{"index":"factorio_mcp_query_capacity"}]},"fields":["name"]}""").jsonObject)
                assertEquals("factorio_mcp_query_capacity", fixture.getValue("objects").jsonArray.single()
                    .jsonObject.getValue("attributes").jsonObject.getValue("name").jsonPrimitive.content)
                fun actions(path: String) = read(path).lineSequence().mapNotNull {
                    it.substringAfter("QUERY_CAPACITY_ACTION ", "").takeIf(String::isNotBlank)
                }.toList()
                val initialActions = actions(serverLog).size
                val initialClientActions = actions(clientLog).size
                val action = tool("ui_action", buildJsonObject {
                    put("action", "click")
                    put("selector", uiSelector("MCP capacity action", "agui::TextButton"))
                })
                assertEquals("completed", action.getValue("dispatch").jsonPrimitive.content)
                nextCrc()
                val authoritativeActions = actions(serverLog).drop(initialActions)
                assertEquals(1, authoritativeActions.size, "Action selectors must traverse beyond the old node cap")
                assertEquals(authoritativeActions, actions(clientLog).drop(initialClientActions))
                val control = tool("input_bindings", buildJsonObject {
                    putJsonArray("ids") { add("move-right") }
                }).getValue("controls").jsonArray.single().jsonObject
                val binding = (control["effective_bindings"] ?: control.getValue("bindings")).jsonArray
                    .map { it.jsonObject }.first {
                        it.getValue("type").jsonPrimitive.content == "Keyboard" &&
                            it.getValue("modifiers").jsonArray.isEmpty()
                    }
                val before = position()
                val initialRecords = positions(serverLog).size
                val initialClientRecords = positions(clientLog).size
                val result = tool("input", buildJsonObject {
                    putJsonArray("timeline") {
                        addJsonObject {
                            put("device", "keyboard")
                            put("key", binding.getValue("name"))
                            put("tick", "0-19")
                        }
                    }
                })
                assertEquals("completed", result.getValue("status").jsonPrimitive.content)
                assertEquals(20, result.getValue("evaluated_ticks").jsonPrimitive.int)
                nextCrc()
                val after = position()
                assertTrue(after.getValue("x").jsonPrimitive.double > before.getValue("x").jsonPrimitive.double)
                val authoritative = positions(serverLog).drop(initialRecords)
                assertTrue(authoritative.isNotEmpty())
                assertEquals(authoritative, positions(clientLog).drop(initialClientRecords))
                nextCrc()
                assertEquals(after, position(), "Released movement must stop after multiplayer delivery settles")
                assertEquals(authoritative, positions(serverLog).drop(initialRecords))
                println("factorio-mcp capacity actions: authoritative movement, released key and later full CRC progress verified")
            } finally {
                withContext(NonCancellable) {
                    try {
                        if (attached) {
                            tool("input", buildJsonObject {
                                put("stop_previous", true)
                                putJsonArray("timeline") {}
                            })
                            tool("detach")
                        }
                    } finally {
                        client.close()
                    }
                }
            }
        }
    }
}
