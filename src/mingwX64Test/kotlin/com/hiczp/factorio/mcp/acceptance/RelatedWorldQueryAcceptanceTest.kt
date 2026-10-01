@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.acceptance

import com.hiczp.factorio.mcp.McpHttpClient
import com.hiczp.factorio.mcp.relatedWorldKinds
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
import kotlin.test.*

class RelatedWorldQueryAcceptanceTest {
    @Test
    fun inventoriesCatalogsForceRecipesAndUiReadsPreserveSynchronization() = runBlocking {
        fun environment(name: String) = getenv(name)?.toKString()?.takeIf { it.isNotBlank() }
        val serverLog = environment("FACTORIO_MCP_UI_SERVER_LOG") ?: return@runBlocking
        val clientLog = checkNotNull(environment("FACTORIO_MCP_UI_CLIENT_LOG"))
        val client = McpHttpClient(checkNotNull(environment("FACTORIO_MCP_ACCEPTANCE_URL")))
        val pid = checkNotNull(environment("FACTORIO_MCP_TEST_PID")).toInt()
        fun read(path: String) =
            SystemFileSystem.source(Path(path)).buffered().use { it.readString() }

        fun crc(path: String) = read(path).lineSequence().count { "UI_ACTION_FULL_CRC " in it }
        fun JsonObject.objects() = getValue("objects").jsonArray.map { it.jsonObject }
        fun JsonObject.attributes() = getValue("attributes").jsonObject
        suspend fun query(
            selection: JsonObject,
            fields: List<String>? = null,
            offset: Int = 0,
            limit: Int = 64,
        ) =
            client
                .tool(
                    "world_query",
                    buildJsonObject {
                        put("selection", selection)
                        if (selection["kind"]!!.jsonPrimitive.content in relatedWorldKinds)
                            put("offset", offset)
                        put("limit", limit)
                        fields?.let {
                            putJsonArray("fields") { it.forEach { field -> add(field) } }
                        }
                    },
                )
                .toolValue()

        fun selection(kind: String, type: String? = null, vararg names: String) = buildJsonObject {
            put("kind", kind)
            type?.let { put("type", it) }
            if (names.isNotEmpty()) putJsonArray("names") { names.forEach { add(it) } }
        }
        withTimeout(90_000) {
            try {
                client.initialize()
                client.tool("attach", buildJsonObject { put("pid", pid) }).toolValue()
                val fixture =
                    read(serverLog)
                        .lineSequence()
                        .filter { "UI_ACTION_ACCEPTANCE " in it }
                        .map {
                            Json.parseToJsonElement(it.substringAfter("UI_ACTION_ACCEPTANCE "))
                                .jsonObject
                        }
                        .last { it["kind"]?.jsonPrimitive?.content == "world_fixture" }
                        .getValue("data")
                        .jsonObject
                val inventories = query(selection("inventories"))
                val main =
                    inventories.objects().single {
                        it["name"]?.jsonPrimitive?.content == "character_main"
                    }
                assertTrue(main["size"]!!.jsonPrimitive.int >= 4)
                val inventorySelection = selection("inventory")
                val slots = query(inventorySelection, limit = 512).objects()
                val plates =
                    slots
                        .filter { it["valid_for_read"]!!.jsonPrimitive.boolean }
                        .map { it.attributes() }
                        .filter { it["name"]?.jsonPrimitive?.content == "iron-plate" }
                assertEquals(
                    setOf("normal", "uncommon"),
                    plates
                        .map { it["quality"]!!.jsonObject["name"]!!.jsonPrimitive.content }
                        .toSet(),
                )
                assertEquals(53, plates.sumOf { it["count"]!!.jsonPrimitive.int })
                val filtered = slots.single { it["index"]!!.jsonPrimitive.int == 10 }
                assertEquals(
                    "coal",
                    filtered["filter"]!!.jsonObject["name"]!!.jsonPrimitive.content,
                )
                assertEquals(
                    "uncommon",
                    filtered["filter"]!!.jsonObject["quality"]!!.jsonPrimitive.content,
                )
                val page = query(inventorySelection, offset = 1, limit = 2)
                assertEquals(listOf(2, 3), page.objects().map { it["index"]!!.jsonPrimitive.int })
                assertEquals(3, page["next_offset"]!!.jsonPrimitive.int)
                assertTrue(page["truncated"]!!.jsonPrimitive.boolean)
                assertTrue(
                    slots.any {
                        !it["valid_for_read"]!!.jsonPrimitive.boolean && it.attributes().isEmpty()
                    }
                )

                val entityPositions =
                    query(
                        buildJsonObject {
                            put("kind", "entities")
                            put("name", "steel-chest")
                            putJsonObject("position") {
                                put("x", 7)
                                put("y", 0)
                            }
                            put("radius", 4)
                        },
                        listOf("unit_number", "position"),
                    )
                        .objects()
                        .associate {
                            it.attributes().getValue("unit_number") to
                                    it.attributes().getValue("position")
                        }

                fun owned(kind: String, unit: JsonElement, inventory: String? = null) =
                    buildJsonObject {
                        put("kind", kind)
                        putJsonObject("owner") {
                            put("kind", "entities")
                            put("unit_number", unit)
                            put("position", entityPositions.getValue(unit))
                        }
                        inventory?.let { put("inventory", it) }
                    }

                val chest = fixture.getValue("chest")
                val unindexed =
                    client.tool(
                        "world_query",
                        buildJsonObject {
                            putJsonObject("selection") {
                                put("kind", "entities")
                                put("unit_number", chest)
                            }
                        },
                    )
                assertTrue(unindexed["isError"]!!.jsonPrimitive.boolean)
                val chestInventory = query(owned("inventories", chest)).objects().single()
                assertEquals("chest", chestInventory["name"]!!.jsonPrimitive.content)
                assertEquals(4, chestInventory["bar"]!!.jsonPrimitive.int)
                val chestSlots = query(owned("inventory", chest, "chest"), limit = 512).objects()
                assertEquals(
                    "coal",
                    chestSlots.first().attributes()["name"]!!.jsonPrimitive.content,
                )
                assertEquals(20, chestSlots.first().attributes()["count"]!!.jsonPrimitive.int)
                assertTrue(
                    query(owned("inventory", fixture.getValue("empty_chest"), "chest"), limit = 512)
                        .objects()
                        .all { !it["valid_for_read"]!!.jsonPrimitive.boolean }
                )
                val wrong =
                    client.tool(
                        "world_query",
                        buildJsonObject {
                            put("selection", owned("inventory", chest, "character_main"))
                        },
                    )
                assertTrue(wrong["isError"]!!.jsonPrimitive.boolean)
                val ambiguous =
                    client.tool(
                        "world_query",
                        buildJsonObject {
                            putJsonObject("selection") {
                                put("kind", "inventories")
                                putJsonObject("owner") {
                                    put("kind", "entities")
                                    put("name", "steel-chest")
                                    putJsonObject("position") {
                                        put("x", 7)
                                        put("y", 0)
                                    }
                                    put("radius", 4)
                                }
                            }
                        },
                    )
                assertTrue(ambiguous["isError"]!!.jsonPrimitive.boolean)

                val recipeNames =
                    arrayOf("iron-gear-wheel", "transport-belt", "__mcp_missing_recipe__")
                val definitions = query(selection("prototypes", "recipe", *recipeNames))
                val available = query(selection("recipes", names = recipeNames))
                assertEquals(
                    JsonArray(listOf(JsonPrimitive("__mcp_missing_recipe__"))),
                    definitions["missing_names"],
                )
                fun JsonObject.recipe(name: String) =
                    objects()
                        .single { it.attributes()["name"]!!.jsonPrimitive.content == name }
                        .attributes()
                assertTrue(definitions.recipe("transport-belt")["enabled"]!!.jsonPrimitive.boolean)
                assertFalse(available.recipe("transport-belt")["enabled"]!!.jsonPrimitive.boolean)
                val gear = available.recipe("iron-gear-wheel")
                assertTrue(
                    gear["ingredients"]!!.jsonArray.any {
                        it.jsonObject["name"]?.jsonPrimitive?.content == "iron-plate"
                    }
                )
                assertEquals(definitions.recipe("iron-gear-wheel")["products"], gear["products"])
                val first = query(selection("prototypes", "item"), listOf("name"), limit = 2)
                val second =
                    query(selection("prototypes", "item"), listOf("name"), offset = 2, limit = 2)
                val full = query(selection("prototypes", "item"), listOf("name"), limit = 4)
                assertEquals(first.objects() + second.objects(), full.objects())
                val missing = query(selection("prototypes", "item", "__mcp_missing_item__"))
                assertTrue(missing.objects().isEmpty())
                val localized =
                    query(
                        selection("prototypes", "item", "burner-mining-drill"),
                        listOf("name", "place_result", "localised_name", "group", "subgroup"),
                    )
                        .objects()
                        .single()
                assertTrue(localized["read_status"]!!.jsonObject.isEmpty())
                assertEquals(
                    "LuaEntityPrototype",
                    localized
                        .attributes()["place_result"]!!
                        .jsonObject["object_name"]!!
                        .jsonPrimitive
                        .content,
                )
                assertNotNull(localized.attributes()["localised_name"])
                for ((type, name) in
                listOf(
                    "entity" to "stone-furnace",
                    "technology" to "automation",
                    "fluid" to "water",
                    "quality" to "uncommon",
                )) {
                    val item = query(selection("prototypes", type, name)).objects().single()
                    assertTrue(
                        item["read_status"]!!.jsonObject.values.none {
                            it.jsonObject["status"]?.jsonPrimitive?.content == "error"
                        },
                        item.toString(),
                    )
                }
                val search =
                    query(
                        buildJsonObject {
                            put("kind", "prototypes")
                            put("type", "item")
                            put("search", "IRON-PLATE")
                        }
                    )
                        .objects()
                assertTrue(search.isNotEmpty())
                assertTrue(
                    search.all {
                        it.attributes()["name"]!!.jsonPrimitive.content.contains("iron-plate")
                    }
                )
                repeat(20) {
                    query(inventorySelection)
                    query(selection("recipes", names = recipeNames))
                    query(owned("inventory", chest, "chest"))
                    if (it % 5 == 0) client.tool("ui_read").toolValue()
                }
                val clientCrc = crc(clientLog)
                val serverCrc = crc(serverLog)
                while (crc(clientLog) < clientCrc + 2 || crc(serverLog) < serverCrc + 2) delay(100)
                assertFalse(read(clientLog).contains("desynchron", true))
                assertFalse(read(serverLog).contains("desynchron", true))
            } finally {
                client.close()
            }
        }
    }
}
