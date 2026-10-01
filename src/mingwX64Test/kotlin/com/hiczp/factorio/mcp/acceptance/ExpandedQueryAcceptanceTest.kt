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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Live-client reads and synchronized chat against the disposable local scenario. */
class ExpandedQueryAcceptanceTest {
    private fun environment(name: String) = getenv(name)?.toKString()?.takeIf { it.isNotBlank() }

    private fun read(path: String) =
        SystemFileSystem.source(Path(path)).buffered().use { it.readString() }

    private fun records(path: String) =
        read(path)
            .lineSequence()
            .filter { "UI_ACTION_ACCEPTANCE " in it }
            .map { Json.parseToJsonElement(it.substringAfter("UI_ACTION_ACCEPTANCE ")).jsonObject }
            .toList()

    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject

    private fun JsonObject.objects() = getValue("objects").jsonArray.map { it.jsonObject }

    @Test
    fun expandedReadsAndChatPreserveAuthoritativeMultiplayerState() = runBlocking {
        val serverLog = environment("FACTORIO_MCP_UI_SERVER_LOG") ?: return@runBlocking
        val clientLog = checkNotNull(environment("FACTORIO_MCP_UI_CLIENT_LOG"))
        val client = McpHttpClient(checkNotNull(environment("FACTORIO_MCP_ACCEPTANCE_URL")))
        val pid = checkNotNull(environment("FACTORIO_MCP_TEST_PID")).toInt()

        suspend fun query(request: String) = client.tool("world_query", json(request)).toolValue()

        suspend fun inspect(
            target: JsonObject,
            path: JsonArray = JsonArray(emptyList()),
            mode: String = "values",
            fields: List<String>? = null,
            limit: Int = 64,
        ): JsonObject {
            return client
                .tool(
                    "world_query",
                    buildJsonObject {
                        putJsonObject("selection") {
                            put("kind", "inspect")
                            put("target", target)
                            put("path", path)
                        }
                        put("mode", mode)
                        put("limit", limit)
                        fields?.let { putJsonArray("fields") { it.forEach { add(it) } } }
                    },
                )
                .toolValue()
                .objects()
                .single()
        }

        withTimeout(120_000) {
            try {
                client.initialize()
                client.tool("attach", buildJsonObject { put("pid", pid) }).toolValue()
                val fixture =
                    records(serverLog)
                        .last { it["kind"]?.jsonPrimitive?.content == "query_details" }
                        .getValue("data")
                        .jsonObject

                fun target(name: String) = buildJsonObject {
                    put("kind", "entities")
                    put("unit_number", fixture.getValue(name))
                    put(
                        "area",
                        json("""{"left_top":{"x":-16,"y":-16},"right_bottom":{"x":16,"y":16}}"""),
                    )
                }

                val surface = fixture.getValue("surface")
                val area = json("""{"left_top":{"x":-16,"y":-16},"right_bottom":{"x":16,"y":16}}""")
                val types = JsonArray(listOf("transport-belt", "mining-drill").map(::JsonPrimitive))
                val selected =
                    client
                        .tool(
                            "world_query",
                            buildJsonObject {
                                putJsonObject("selection") {
                                    put("kind", "entities")
                                    put("area", area)
                                    put("type", types)
                                }
                                put("surface", surface)
                                putJsonArray("fields") {
                                    add("name")
                                    add("type")
                                    add("unit_number")
                                }
                            },
                        )
                        .toolValue()
                assertFalse(selected.getValue("truncated").jsonPrimitive.boolean)
                assertEquals(
                    types.map { it.jsonPrimitive.content }.toSet(),
                    selected
                        .objects()
                        .map {
                            it.getValue("attributes")
                                .jsonObject
                                .getValue("type")
                                .jsonPrimitive
                                .content
                        }
                        .toSet(),
                )
                for (detail in listOf("grid", "entities")) {
                    val overview =
                        client
                            .tool(
                                "world_overview",
                                buildJsonObject {
                                    put("area", area)
                                    put("surface", surface)
                                    put("type", types)
                                    put("detail", detail)
                                    put("name", "electric-mining-drill")
                                },
                            )
                            .toolValue()
                    val names =
                        if (detail == "entities")
                            overview.objects().map {
                                it.getValue("attributes")
                                    .jsonObject
                                    .getValue("name")
                                    .jsonPrimitive
                                    .content
                            }
                        else
                            overview.objects().flatMap {
                                it.getValue("entity_groups").jsonArray.map { group ->
                                    group.jsonObject.getValue("name").jsonPrimitive.content
                                }
                            }
                    assertEquals(listOf("electric-mining-drill"), names)
                }
                suspend fun details(name: String) =
                    client
                        .tool(
                            "world_query",
                            buildJsonObject {
                                put("selection", target(name))
                                put("surface", surface)
                                putJsonArray("include") {
                                    add("recipe")
                                    add("fluids")
                                    add("filters")
                                }
                            },
                        )
                        .toolValue()
                        .objects()
                        .single()

                val machine = details("machine")
                val recipe = machine.getValue("details").jsonObject.getValue("recipe").jsonObject
                assertEquals(
                    "iron-gear-wheel",
                    recipe.getValue("recipe").jsonObject.getValue("name").jsonPrimitive.content,
                )
                assertEquals(
                    "uncommon",
                    recipe.getValue("quality").jsonObject.getValue("name").jsonPrimitive.content,
                )
                assertEquals(
                    1234.0,
                    details("tank")
                        .getValue("details")
                        .jsonObject
                        .getValue("fluids")
                        .jsonObject
                        .getValue("water")
                        .jsonPrimitive
                        .double,
                )
                val filters =
                    details("inserter")
                        .getValue("details")
                        .jsonObject
                        .getValue("filters")
                        .jsonObject
                assertTrue(
                    filters
                        .getValue("attributes")
                        .jsonObject
                        .getValue("use_filters")
                        .jsonPrimitive
                        .boolean
                )
                assertEquals(
                    "iron-plate",
                    filters
                        .getValue("slots")
                        .jsonArray
                        .first()
                        .jsonObject
                        .getValue("filter")
                        .jsonObject
                        .getValue("name")
                        .jsonPrimitive
                        .content,
                )

                // Inspect with an explicit surface because the root is deliberately off the
                // observer's surface.
                val inspectTarget = json("""{"kind":"game"}""")
                val members = inspect(inspectTarget, mode = "members", limit = 256)
                assertTrue(
                    members.getValue("members").jsonArray.any {
                        it.jsonObject["name"]?.jsonPrimitive?.content == "players"
                    }
                )
                val players =
                    query(
                        """{"selection":{"kind":"players"},"fields":["name","index","connected","position","surface","character"]}"""
                    )
                assertTrue(players.objects().isNotEmpty())
                for (entry in players.objects()) {
                    val attributes = entry.getValue("attributes").jsonObject
                    val named =
                        client
                            .tool(
                                "world_query",
                                buildJsonObject {
                                    putJsonObject("selection") {
                                        put("kind", "player")
                                        put("player", attributes.getValue("name"))
                                    }
                                    putJsonArray("fields") {
                                        add("index")
                                        add("name")
                                        add("position")
                                        add("surface")
                                    }
                                },
                            )
                            .toolValue()
                            .objects()
                            .single()
                            .getValue("attributes")
                            .jsonObject
                    for (key in listOf("index", "name", "position", "surface")) assertEquals(
                        attributes[key],
                        named[key],
                    )
                }
                val playerEntries =
                    inspect(
                        inspectTarget,
                        JsonArray(listOf(json("""{"property":"players"}"""))),
                        mode = "entries",
                    )
                assertEquals(
                    players.objects().size,
                    playerEntries.getValue("entries").jsonArray.size,
                )

                val machineInspection =
                    client
                        .tool(
                            "world_query",
                            buildJsonObject {
                                put("surface", surface)
                                putJsonObject("selection") {
                                    put("kind", "inspect")
                                    put("target", target("machine"))
                                    putJsonArray("path") {
                                        add(json("""{"method":"get_recipe"}"""))
                                    }
                                }
                                putJsonArray("fields") {
                                    add("name")
                                    add("ingredients")
                                    add("products")
                                    add("enabled")
                                }
                            },
                        )
                        .toolValue()
                        .objects()
                        .single()
                assertEquals(
                    "iron-gear-wheel",
                    machineInspection
                        .getValue("attributes")
                        .jsonObject
                        .getValue("name")
                        .jsonPrimitive
                        .content,
                )
                assertTrue(
                    machineInspection
                        .getValue("attributes")
                        .jsonObject
                        .getValue("ingredients")
                        .jsonArray
                        .isNotEmpty()
                )
                val fluids =
                    client
                        .tool(
                            "world_query",
                            buildJsonObject {
                                put("surface", surface)
                                putJsonObject("selection") {
                                    put("kind", "inspect")
                                    put("target", target("tank"))
                                    putJsonArray("path") {
                                        add(json("""{"property":"fluidbox"}"""))
                                    }
                                }
                                put("mode", "entries")
                            },
                        )
                        .toolValue()
                        .objects()
                        .single()
                        .getValue("entries")
                        .jsonArray
                assertTrue(
                    fluids.any {
                        it.jsonObject["value"]?.jsonObject?.get("name")?.jsonPrimitive?.content ==
                                "water"
                    }
                )

                val beforeChat = client.tool("chat_read").toolValue()
                val cursor = beforeChat.getValue("cursor")
                val message =
                    "MCP chat acceptance ${players.getValue("tick").jsonPrimitive.content} 中文"
                val sent =
                    client.tool("chat_send", buildJsonObject { put("text", message) }).toolValue()
                assertEquals("completed", sent.getValue("dispatch").jsonPrimitive.content)
                suspend fun chatArrived(path: String) =
                    records(path).any {
                        it["kind"]?.jsonPrimitive?.content == "chat" &&
                                it["data"]?.jsonObject?.get("message")?.jsonPrimitive?.content ==
                                message
                    }
                while (!chatArrived(serverLog) || !chatArrived(clientLog)) delay(100)
                fun chatEvent(path: String) =
                    records(path).single {
                        it["kind"]?.jsonPrimitive?.content == "chat" &&
                                it["data"]?.jsonObject?.get("message")?.jsonPrimitive?.content ==
                                message
                    }

                val authoritativeChat = chatEvent(serverLog)
                assertEquals(authoritativeChat, chatEvent(clientLog))
                assertFalse(authoritativeChat.getValue("admin").jsonPrimitive.boolean)
                val observed =
                    client.tool("chat_read", buildJsonObject { put("after", cursor) }).toolValue()
                assertTrue(
                    observed.getValue("messages").jsonArray.any {
                        it.jsonObject.getValue("text").jsonPrimitive.content.contains(message) ||
                                it.jsonObject.getValue("raw").jsonPrimitive.content.contains(message)
                    }
                )
                val repeated =
                    client
                        .tool(
                            "chat_read",
                            buildJsonObject { put("after", observed.getValue("cursor")) },
                        )
                        .toolValue()
                assertTrue(repeated.getValue("messages").jsonArray.isEmpty())
                assertTrue(
                    client
                        .tool("chat_send", buildJsonObject { put("text", "/help") })
                        .getValue("isError")
                        .jsonPrimitive
                        .boolean
                )

                fun crc(path: String) =
                    read(path).lineSequence().count { "UI_ACTION_FULL_CRC " in it }

                val clientCrc = crc(clientLog)
                val serverCrc = crc(serverLog)
                while (crc(clientLog) < clientCrc + 2 || crc(serverLog) < serverCrc + 2) delay(100)
                for (path in listOf(clientLog, serverLog)) assertFalse(
                    read(path).contains("desynchron", ignoreCase = true)
                )
            } finally {
                client.close()
            }
        }
    }
}
