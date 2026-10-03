@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.acceptance

import com.hiczp.factorio.mcp.McpHttpClient
import com.hiczp.factorio.mcp.toolValue
import com.hiczp.factorio.mcp.uiSelector
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

/** Fixture buttons change authoritative controller state product calls only observe it. */
class PlayerContextAcceptanceTest {
    @Test
    fun relatedCharactersVehiclesQuickbarAndResearchPreserveNativeState() = runBlocking {
        fun environment(name: String) = getenv(name)?.toKString()?.takeIf { it.isNotBlank() }
        val serverLog = environment("FACTORIO_MCP_UI_SERVER_LOG") ?: return@runBlocking
        val clientLog = checkNotNull(environment("FACTORIO_MCP_UI_CLIENT_LOG"))
        val pid = checkNotNull(environment("FACTORIO_MCP_TEST_PID")).toInt()
        val client = McpHttpClient(checkNotNull(environment("FACTORIO_MCP_ACCEPTANCE_URL")))
        fun read(path: String) =
            SystemFileSystem.source(Path(path)).buffered().use { it.readString() }

        fun crc(path: String) = read(path).lineSequence().count { "UI_ACTION_FULL_CRC " in it }
        fun JsonObject.objects() = getValue("objects").jsonArray.map { it.jsonObject }
        fun JsonObject.attributes() = getValue("attributes").jsonObject
        suspend fun query(selection: JsonObject, fields: List<String>? = null) =
            client
                .tool(
                    "world_query",
                    buildJsonObject {
                        put("selection", selection)
                        fields?.let { putJsonArray("fields") { it.forEach { add(it) } } }
                    },
                )
                .toolValue()

        fun selection(kind: String, owner: String? = null, inventory: String? = null) =
            buildJsonObject {
                put("kind", kind)
                owner?.let { putJsonObject("owner") { put("kind", it) } }
                inventory?.let { put("inventory", it) }
            }

        suspend fun player() =
            query(
                selection("player"),
                listOf(
                    "character",
                    "vehicle",
                    "physical_vehicle",
                    "physical_controller_type",
                    "driving",
                ),
            )

        suspend fun click(text: String) =
            client
                .tool(
                    "ui_action",
                    buildJsonObject {
                        put("action", "click")
                        put("selector", uiSelector(text, "agui::TextButton"))
                    },
                )
                .toolValue()

        suspend fun waitPlayer(predicate: (JsonObject) -> Boolean): JsonObject {
            while (true) {
                val value = player()
                if (predicate(value)) return value
                delay(20)
            }
        }

        suspend fun absent(kind: String) {
            val value = query(selection(kind))
            assertEquals("nil", value["availability"]!!.jsonPrimitive.content)
            assertTrue(value.objects().isEmpty())
            val inventory =
                client.tool(
                    "world_query",
                    buildJsonObject { put("selection", selection("inventory", kind)) },
                )
            assertTrue(inventory["isError"]!!.jsonPrimitive.boolean)
        }
        withTimeout(120_000) {
            try {
                client.initialize()
                client.tool("attach", buildJsonObject { put("pid", pid) }).toolValue()
                val initialPlayer = player()
                val initial =
                    query(
                        selection("character"),
                        listOf(
                            "unit_number",
                            "health",
                            "max_health",
                            "selected_gun_index",
                            "surface",
                        ),
                    )
                val character = initial.objects().single().attributes()
                val unit = character.getValue("unit_number")
                assertTrue(character.getValue("health").jsonPrimitive.double > 0)
                assertTrue(
                    character.getValue("health").jsonPrimitive.double <=
                            character.getValue("max_health").jsonPrimitive.double
                )
                assertEquals(1, character.getValue("selected_gun_index").jsonPrimitive.int)
                val inventory =
                    query(selection("inventory", "character"), listOf("name", "count", "quality"))
                val discovery = query(selection("inventories", "character"))
                val names = discovery.objects().map { it["name"]!!.jsonPrimitive.content }
                assertTrue("character_guns" in names && "character_ammo" in names)
                val guns =
                    query(
                        selection("inventory", "character", "character_guns"),
                        listOf("name", "count"),
                    )
                assertTrue(
                    guns.objects().any {
                        it.attributes()["name"]?.jsonPrimitive?.content == "pistol"
                    }
                )
                val ammo =
                    query(
                        selection("inventory", "character", "character_ammo"),
                        listOf("name", "count", "ammo"),
                    )
                assertTrue(
                    ammo.objects().any { it.attributes()["count"]?.jsonPrimitive?.int == 10 }
                )
                absent("vehicle")
                absent("physical_vehicle")

                val quickbarSelection = buildJsonObject {
                    put("kind", "quickbar")
                    putJsonArray("slots") { listOf(1, 2, 65536).forEach { add(it) } }
                    putJsonArray("screen_pages") { add(1) }
                }
                val quickbar = query(quickbarSelection)
                val filter = quickbar.objects()[0].attributes().getValue("filter").jsonObject
                assertEquals("iron-plate", filter.getValue("name").jsonPrimitive.content)
                assertEquals("uncommon", filter.getValue("quality").jsonPrimitive.content)
                assertEquals(
                    "nil",
                    quickbar
                        .objects()[1]["read_status"]!!
                        .jsonObject["filter"]!!
                        .jsonObject["status"]!!
                        .jsonPrimitive
                        .content,
                )
                assertEquals(
                    "error",
                    quickbar
                        .objects()[2]["read_status"]!!
                        .jsonObject["filter"]!!
                        .jsonObject["status"]!!
                        .jsonPrimitive
                        .content,
                )
                assertEquals(
                    3,
                    quickbar["active_pages"]!!
                        .jsonArray
                        .single()
                        .jsonObject
                        .attributes()["page"]!!
                        .jsonPrimitive
                        .int,
                )
                val technologySelection = buildJsonObject {
                    put("kind", "technologies")
                    putJsonArray("names") {
                        add("electronics")
                        add("automation")
                        add("mcp-absent-technology")
                    }
                }
                val technologies =
                    query(
                        technologySelection,
                        listOf(
                            "name",
                            "researched",
                            "enabled",
                            "level",
                            "prerequisites",
                            "prototype",
                            "saved_progress",
                        ),
                    )
                val byName =
                    technologies.objects().associate {
                        it.attributes().getValue("name").jsonPrimitive.content to it.attributes()
                    }
                assertTrue(byName.getValue("electronics")["researched"]!!.jsonPrimitive.boolean)
                assertFalse(byName.getValue("automation")["researched"]!!.jsonPrimitive.boolean)
                assertEquals(
                    listOf(JsonPrimitive("mcp-absent-technology")),
                    technologies["missing_names"]!!.jsonArray.toList(),
                )
                assertEquals(
                    "LuaTechnologyPrototype",
                    byName
                        .getValue("automation")["prototype"]!!
                        .jsonObject["object_name"]!!
                        .jsonPrimitive
                        .content,
                )
                assertTrue(byName.getValue("automation")["prerequisites"] is JsonObject)
                val forceBefore = query(selection("force"))
                val recipes =
                    query(
                        buildJsonObject {
                            put("kind", "recipes")
                            putJsonObject("product") {
                                put("type", "item")
                                put("name", "iron-plate")
                            }
                        }
                    )
                assertTrue(recipes.objects().isNotEmpty())
                assertTrue(
                    recipes.objects().all { recipe ->
                        recipe.attributes()["products"]!!.jsonArray.any {
                            it.jsonObject["type"]?.jsonPrimitive?.content == "item" &&
                                    it.jsonObject["name"]?.jsonPrimitive?.content == "iron-plate"
                        }
                    }
                )
                assertFalse(recipes["truncated"]!!.jsonPrimitive.boolean)
                fun catalog(type: String, name: String) = buildJsonObject {
                    put("kind", "prototypes")
                    put("type", type)
                    putJsonArray("names") { add(name) }
                }

                val entity =
                    query(
                        catalog("entity", "electric-mining-drill"),
                        listOf(
                            "type",
                            "group",
                            "subgroup",
                            "hidden_in_factoriopedia",
                            "factoriopedia_description",
                            "factoriopedia_alternative",
                        ),
                    )
                        .objects()
                        .single()
                assertEquals("mining-drill", entity.attributes()["type"]!!.jsonPrimitive.content)
                assertFalse(
                    entity["read_status"]!!.jsonObject.values.any {
                        it.jsonObject["status"]?.jsonPrimitive?.content == "error"
                    }
                )
                val group =
                    entity.attributes()["group"]!!.jsonObject["name"]!!.jsonPrimitive.content
                val subgroup =
                    entity.attributes()["subgroup"]!!.jsonObject["name"]!!.jsonPrimitive.content
                assertTrue(
                    query(catalog("item_group", group)).objects().single().attributes()["subgroups"]
                            is JsonArray
                )
                assertEquals(
                    group,
                    query(catalog("item_subgroup", subgroup))
                        .objects()
                        .single()
                        .attributes()["group"]!!
                        .jsonObject["name"]!!
                        .jsonPrimitive
                        .content,
                )

                click("MCP query vehicle")
                val inVehicle = waitPlayer { "vehicle" in it.objects().single().attributes() }
                val vehicle =
                    query(
                        selection("vehicle"),
                        listOf(
                            "unit_number",
                            "health",
                            "max_health",
                            "speed",
                            "selected_gun_index",
                            "driver_is_gunner",
                        ),
                    )
                        .objects()
                        .single()
                        .attributes()
                assertEquals(0.0, vehicle["speed"]!!.jsonPrimitive.double)
                assertEquals(
                    vehicle["unit_number"],
                    inVehicle
                        .objects()
                        .single()
                        .attributes()["physical_vehicle"]!!
                        .jsonObject["unit_number"],
                )
                assertTrue(query(selection("inventories", "vehicle")).objects().isNotEmpty())
                click("MCP query remote")
                val remote = waitPlayer {
                    val context = it["player"]!!.jsonObject
                    context["surface"]!!.jsonObject["index"] !=
                            context["physical_surface"]!!.jsonObject["index"]
                }
                val physical =
                    remote["player"]!!.jsonObject["physical_surface"]!!.jsonObject["index"]
                val remoteCharacter =
                    query(selection("character"), listOf("unit_number", "health", "surface"))
                assertEquals(unit, remoteCharacter.objects().single().attributes()["unit_number"])
                assertEquals(
                    physical,
                    remoteCharacter.objects().single()["visibility"]!!.jsonObject["surface_index"],
                )
                assertEquals(physical, remoteCharacter["surface"]!!.jsonObject["index"])
                assertEquals(
                    vehicle["unit_number"],
                    query(selection("physical_vehicle"), listOf("unit_number"))
                        .objects()
                        .single()
                        .attributes()["unit_number"],
                )
                val remoteInventory =
                    query(selection("inventory", "character"), listOf("name", "count", "quality"))
                assertEquals(inventory["objects"], remoteInventory["objects"])
                val unavailable =
                    client.tool(
                        "world_query",
                        buildJsonObject { put("selection", selection("inventory")) },
                    )
                assertTrue(unavailable["isError"]!!.jsonPrimitive.boolean)
                val binding =
                    client
                        .tool(
                            "input_bindings",
                            buildJsonObject { putJsonArray("ids") { add("toggle-map") } },
                        )
                        .toolValue()["controls"]!!
                        .jsonArray
                        .single()
                        .jsonObject["bindings"]!!
                        .jsonArray
                        .map { it.jsonObject }
                        .first { it["slot"]!!.jsonPrimitive.content == "keyboard_mouse_primary" }
                assertEquals("Keyboard", binding["type"]!!.jsonPrimitive.content)
                assertTrue(binding["modifiers"]!!.jsonArray.isEmpty())
                client
                    .tool(
                        "ui_action",
                        buildJsonObject {
                            put("action", "press_key")
                            put("key", binding["name"]!!)
                        },
                    )
                    .toolValue()
                waitPlayer {
                    it["player"]!!.jsonObject["surface"]!!.jsonObject["index"] == physical
                }
                click("MCP query vehicle")
                waitPlayer { "vehicle" !in it.objects().single().attributes() }
                click("MCP query spectator")
                waitPlayer { "character" !in it.objects().single().attributes() }
                absent("character")
                click("MCP query spectator")
                waitPlayer {
                    it.objects()
                        .single()
                        .attributes()["character"]
                        ?.jsonObject
                        ?.get("unit_number") == unit
                }
                assertEquals(
                    initialPlayer["player"]!!.jsonObject["physical_position"],
                    player()["player"]!!.jsonObject["physical_position"],
                )
                repeat(3) {
                    assertEquals(
                        technologies["objects"],
                        query(
                            technologySelection,
                            listOf(
                                "name",
                                "researched",
                                "enabled",
                                "level",
                                "prerequisites",
                                "prototype",
                                "saved_progress",
                            ),
                        )["objects"],
                    )
                    assertEquals(forceBefore["objects"], query(selection("force"))["objects"])
                    assertEquals(quickbar["objects"], query(quickbarSelection)["objects"])
                    client.tool("ui_read").toolValue()
                }
                val clientCrc = crc(clientLog)
                val serverCrc = crc(serverLog)
                while (crc(clientLog) <= clientCrc || crc(serverLog) <= serverCrc) delay(50)
                assertFalse(read(clientLog).contains("desynchron", ignoreCase = true))
            } finally {
                client.close()
            }
        }
    }
}
