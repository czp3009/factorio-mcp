@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.acceptance

import com.hiczp.factorio.mcp.McpHttpClient
import com.hiczp.factorio.mcp.toolValue
import com.hiczp.factorio.mcp.worldQuery
import kotlinx.cinterop.toKString
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.*
import platform.posix.getenv
import kotlin.test.*

/** Direct native observations against the stock/DLC ui-actions scenario. */
class WorldQueryExpansionAcceptanceTest {
    @Test
    fun batchesCatalogsAndNativePathsRemainPassiveOnTheAuthoritativeWorld() = runBlocking {
        fun environment(name: String) = checkNotNull(getenv(name)) { "Select $name explicitly" }.toKString()
        fun read(path: String) = SystemFileSystem.source(Path(path)).buffered().use { it.readString() }
        fun json(value: String) = Json.parseToJsonElement(value).jsonObject
        fun JsonObject.rows() = getValue("objects").jsonArray.map { it.jsonObject }
        fun JsonObject.row() = rows().single()
        fun JsonObject.reads() = getValue("reads").jsonArray.map { it.jsonObject }
        fun property(name: String) = buildJsonObject { put("property", name) }
        fun method(name: String, vararg arguments: JsonElement) = buildJsonObject {
            put("method", name)
            if (arguments.isNotEmpty()) put("arguments", JsonArray(arguments.toList()))
        }

        val clientLog = environment("FACTORIO_MCP_UI_CLIENT_LOG")
        val serverLog = environment("FACTORIO_MCP_UI_SERVER_LOG")
        fun crc(path: String) = read(path).lineSequence().count { "UI_ACTION_FULL_CRC " in it }
        val fixture = read(serverLog).lineSequence().filter { "UI_ACTION_ACCEPTANCE " in it }
            .map { json(it.substringAfter("UI_ACTION_ACCEPTANCE ")) }
            .last { it["kind"]?.jsonPrimitive?.content == "query_expansion" }.getValue("data").jsonObject
        val surface = fixture.getValue("surface")
        val client = McpHttpClient(environment("FACTORIO_MCP_ACCEPTANCE_URL"))
        var attached = false
        withTimeout(600_000) {
            try {
                client.initialize()
                client.tool("attach", buildJsonObject { put("pid", environment("FACTORIO_MCP_TEST_PID").toInt()) })
                    .toolValue()
                attached = true
                suspend fun query(request: String) = client.worldQuery(json(request)).toolValue()
                suspend fun inspect(
                    target: JsonObject,
                    path: List<JsonObject> = emptyList(),
                    options: JsonObject = buildJsonObject {},
                ) = client.worldQuery(buildJsonObject {
                    putJsonObject("selection") {
                        put("kind", "inspect")
                        put("target", target)
                        put("path", JsonArray(path))
                    }
                    options.forEach { (key, value) -> put(key, value) }
                }).toolValue().row()

                val before =
                    query("""{"selection":{"kind":"player"},"fields":["position","physical_position","surface","physical_surface","controller_type"]}""")
                val inventoryEnums = inspect(json("""{"kind":"defines"}"""), listOf(property("inventory")))
                    .getValue("value").jsonObject
                val inputIndex = inventoryEnums.getValue("crafter_input")
                val outputIndex = inventoryEnums.getValue("crafter_output")
                assertNotEquals(inputIndex, outputIndex)
                val catalogs = inspect(json("""{"kind":"prototypes"}"""), options = json("""{"mode":"members"}"""))
                    .getValue("members").jsonArray.map { it.jsonObject }
                    .filter {
                        it["api"]?.jsonObject?.get("read_type")?.let { value ->
                            value is JsonObject && value["complex_type"]?.jsonPrimitive?.content == "LuaCustomTable"
                        } == true
                    }.map { it.getValue("name").jsonPrimitive.content }
                assertTrue(catalogs.size > 8)
                for (catalog in catalogs) {
                    val result = client.worldQuery(buildJsonObject {
                        putJsonObject("selection") {
                            put("kind", "prototypes")
                            put("type", catalog)
                        }
                        putJsonArray("fields") {}
                        put("limit", 2)
                    }).toolValue()
                    assertTrue(result.rows().all { it.getValue("read_status").jsonObject.isEmpty() }, catalog)
                    assertTrue(result.rows().size <= 2)
                }
                val batch = query(
                    """{"surface":$surface,"selection":{"kind":"entities","type":"assembling-machine","area":{"left_top":{"x":-12,"y":-4},"right_bottom":{"x":4,"y":4}}},"fields":["crafting_speed","products_finished"],"reads":[
                  {"path":[{"method":"get_recipe"}],"fields":["name","ingredients","products"]},
                  {"path":[{"method":"get_recipe","result":2}],"fields":["name"]},
                  {"path":[{"method":"get_inventory","arguments":[$inputIndex]},{"method":"get_contents"}]},
                  {"path":[{"method":"get_output_inventory"},{"method":"get_contents"}]},
                  {"path":[{"method":"get_module_inventory"},{"method":"get_contents"}]},
                  {"path":[{"property":"__missing_native_attribute"}]},
                  {"path":[{"property":"crafting_speed"}]},
                  {"path":[{"property":"crafting_speed"}]}
                ]}"""
                )
                assertEquals(2, batch.rows().size)
                assertTrue(batch.getValue("selection_complete").jsonPrimitive.boolean)
                assertNull(batch["next_offset"])
                val machine =
                    batch.rows().single { it.getValue("reference").jsonObject["unit_number"] == fixture["machine"] }
                val reads = machine.reads()
                assertEquals(8, reads.size)
                assertEquals(
                    "iron-gear-wheel",
                    reads[0].getValue("attributes").jsonObject.getValue("name").jsonPrimitive.content
                )
                assertEquals(
                    "uncommon",
                    reads[1].getValue("attributes").jsonObject.getValue("name").jsonPrimitive.content
                )
                assertEquals(
                    34,
                    reads[2].getValue("value").jsonArray.single().jsonObject.getValue("count").jsonPrimitive.int
                )
                assertEquals(
                    5,
                    reads[3].getValue("value").jsonArray.single().jsonObject.getValue("count").jsonPrimitive.int
                )
                assertEquals(
                    "speed-module",
                    reads[4].getValue("value").jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content
                )
                assertEquals(
                    "error",
                    reads[5].getValue("read_status").jsonObject.getValue("status").jsonPrimitive.content
                )
                assertEquals(reads[6], reads[7])
                assertEquals(
                    reads[2].getValue("path").jsonArray[1].jsonObject.getValue("method"),
                    JsonPrimitive("get_contents")
                )
                val reference = machine.getValue("reference").jsonObject
                val exact = client.worldQuery(buildJsonObject {
                    put("surface", surface)
                    putJsonObject("selection") {
                        put("kind", "entities")
                        put("unit_number", reference.getValue("unit_number"))
                        put("position", reference.getValue("position"))
                    }
                    putJsonArray("fields") { add("name") }
                    putJsonArray("reads") {
                        addJsonObject {
                            putJsonArray("path") { add(method("get_recipe")) }
                            putJsonArray("fields") { add("name") }
                        }
                    }
                }).toolValue().row()
                assertEquals(
                    reads[0].getValue("attributes").jsonObject["name"],
                    exact.reads().single().getValue("attributes").jsonObject["name"]
                )
                val filtered = query(
                    """{"surface":$surface,"selection":{"kind":"entities","area":{"left_top":{"x":4,"y":-4},"right_bottom":{"x":16,"y":4}},"filters":{"force":"player","quality":"rare"}},"fields":["name","quality"],"reads":[{"path":[{"method":"get_inventory","arguments":[${
                        inventoryEnums.getValue("chest")
                    }]},{"method":"get_contents"}]}]}"""
                )
                assertEquals(fixture["other_chest"], filtered.row().getValue("reference").jsonObject["unit_number"])
                assertEquals(
                    11,
                    filtered.row().reads().single()
                        .getValue("value").jsonArray.single().jsonObject.getValue("count").jsonPrimitive.int
                )
                val qualitySpeed = inspect(
                    json("""{"kind":"prototype","type":"entity","name":"assembling-machine-2"}"""),
                    listOf(method("get_crafting_speed", JsonPrimitive("rare")))
                )
                    .getValue("value").jsonPrimitive.double
                assertTrue(qualitySpeed > 0.75)
                val surfaceTarget = buildJsonObject {
                    put("kind", "surface")
                    put("name", surface)
                }
                assertEquals(
                    10.0,
                    inspect(
                        surfaceTarget,
                        listOf(method("get_property", JsonPrimitive("gravity")))
                    ).getValue("value").jsonPrimitive.double
                )
                val chunks = inspect(
                    surfaceTarget,
                    listOf(method("get_chunks")),
                    json("""{"mode":"entries","limit":4,"depth":4}""")
                )
                val page = inspect(
                    surfaceTarget,
                    listOf(method("get_chunks")),
                    json("""{"mode":"entries","offset":2,"limit":2,"depth":4}""")
                )
                assertEquals(
                    chunks.getValue("entries").jsonArray.drop(2),
                    page.getValue("entries").jsonArray.toList()
                )
                assertTrue(page.getValue("has_more").jsonPrimitive.boolean)
                assertNull(page["total"])
                val trains = inspect(
                    json("""{"kind":"game"}"""),
                    listOf(property("train_manager"), method("get_trains", buildJsonObject {})),
                    json("""{"mode":"entries","limit":32,"reads":[{"fields":["id","manual_mode"]},{"path":[{"method":"get_schedule"},{"method":"get_records"}],"depth":6}]}""")
                )
                val train = trains.getValue("entries").jsonArray.map { it.jsonObject }.single {
                    it.getValue("value").jsonObject["id"] == fixture["train_id"]
                }
                val schedule = train.reads()[1].getValue("value").jsonArray
                assertEquals(
                    listOf("MCP query alpha", "MCP query beta"),
                    schedule.map { it.jsonObject.getValue("station").jsonPrimitive.content })
                assertEquals(
                    300,
                    schedule[0].jsonObject.getValue("wait_conditions").jsonArray[0].jsonObject.getValue("ticks").jsonPrimitive.int
                )
                val inventory = query("""{"selection":{"kind":"inventory"},"fields":["name"]}""")
                val blueprintSlot = inventory.rows()
                    .single { it.getValue("attributes").jsonObject["name"] == JsonPrimitive("blueprint") }
                    .getValue("index")
                val blueprint = inspect(
                    json("""{"kind":"player"}"""),
                    listOf(method("get_main_inventory"), buildJsonObject { put("index", blueprintSlot) }),
                    json("""{"fields":[],"reads":[{"path":[{"method":"get_blueprint_entities"}],"depth":8},{"path":[{"method":"get_blueprint_tiles"}]},{"path":[{"method":"get_blueprint_entity_tags","arguments":[1]}]}]}""")
                )
                assertEquals(2, blueprint.reads()[0].getValue("value").jsonArray.size)
                assertEquals(2, blueprint.reads()[1].getValue("value").jsonArray.size)
                assertEquals(
                    "MCP original",
                    blueprint.reads()[2].getValue("value").jsonObject.getValue("source").jsonPrimitive.content
                )
                val statisticsTarget = json("""{"kind":"surface","name":"mcp-query-statistics"}""")
                val statistics = inspect(
                    statisticsTarget,
                    listOf(property("pollution_statistics")),
                    json("""{"fields":["valid","input_counts"]}""")
                )
                assertEquals("LuaPollutionFlowStatistics", statistics.getValue("object_name").jsonPrimitive.content)
                assertEquals("LuaFlowStatistics", statistics.getValue("api_class").jsonPrimitive.content)
                assertTrue(statistics.getValue("attributes").jsonObject.getValue("valid").jsonPrimitive.boolean)
                assertEquals(
                    "error",
                    statistics.getValue("read_status").jsonObject.getValue("input_counts").jsonObject.getValue("status").jsonPrimitive.content
                )
                val after =
                    query("""{"selection":{"kind":"player"},"fields":["position","physical_position","surface","physical_surface","controller_type"]}""")
                assertEquals(before.row().getValue("attributes"), after.row().getValue("attributes"))
                val clientCrc = crc(clientLog)
                val serverCrc = crc(serverLog)
                while (crc(clientLog) < clientCrc + 2 || crc(serverLog) < serverCrc + 2) delay(100)
                assertFalse(read(clientLog).contains("desynchron", true))
                assertFalse(read(serverLog).contains("desynchron", true))
                println("factorio-mcp expanded world reads: ${catalogs.size} catalogs, native enums, remote batch/exact contents, quality, chunks, schedules, blueprints and passive statistics guards; two full CRC rounds")
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
