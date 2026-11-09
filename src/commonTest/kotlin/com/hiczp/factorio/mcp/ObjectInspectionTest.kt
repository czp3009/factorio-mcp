package com.hiczp.factorio.mcp

import kotlin.test.*
import kotlinx.serialization.json.*

class ObjectInspectionTest {
    @Test
    fun inspectionPathsAndPassiveMethodDataHaveNoSmallBusinessCeilings() {
        val path = JsonArray(List(40) { buildJsonObject { put("property", "children") } })
        val request = buildJsonObject {
            putJsonObject("selection") {
                put("kind", "inspect")
                putJsonObject("target") { put("kind", "player") }
                put("path", path)
            }
        }
        val query = parseWorldQuery(request)
        assertEquals(40, query.arguments.getValue("inspection").jsonObject.getValue("path").jsonArray.size)
        val method = buildJsonObject {
            put("method", "get_contents")
            putJsonArray("arguments") { repeat(20) { add("x".repeat(1024)) } }
            put("result", 20)
        }
        parseWorldQuery(
            JsonObject(
                request + ("selection" to JsonObject(
                    request.getValue("selection").jsonObject +
                            ("path" to JsonArray(listOf(method)))
                ))
            )
        )
    }

    @Test
    fun boundedInspectionPreviewsAreNotCoercedIntoLegacyArrays() {
        val value =
            decodeWorldQuery(
                """{"observation":"object_inspection","objects":[{"attributes":{"products":{"observation":"partial","value":[],"reason":"collection_bound"}}}]}"""
            )
        assertEquals(
            "partial",
            value
                .getValue("objects")
                .jsonArray
                .single()
                .jsonObject
                .getValue("attributes")
                .jsonObject
                .getValue("products")
                .jsonObject
                .getValue("observation")
                .jsonPrimitive
                .content,
        )
    }

    private fun query(text: String) = parseWorldQuery(Json.parseToJsonElement(text).jsonObject)

    @Test
    fun discoveryAndTraversalAreDataAndMutationsAreRejected() {
        val value =
            query(
                """{"selection":{"kind":"inspect","target":{"kind":"entities","unit_number":42},"path":[{"method":"get_recipe"},{"property":"products"}]},"mode":"entries","limit":5}"""
            )
        assertEquals(
            "entities",
            value.arguments.getValue("selection").jsonObject.getValue("kind").jsonPrimitive.content,
        )
        assertEquals(
            2,
            value.arguments.getValue("inspection").jsonObject.getValue("path").jsonArray.size,
        )
        for (method in
        listOf(
            "destroy",
            "set_recipe",
            "get_or_create_control_behavior",
            "can_insert",
            "request_translation",
            "get_item_production_statistics",
            "get_fluid_production_statistics",
            "get_entity_build_count_statistics",
            "get_kill_count_statistics",
            "get_input_count",
            "get_output_count",
            "get_storage_count",
            "get_flow_count",
            "get_current_input_sample",
            "get_current_output_sample",
            "get_capacity",
            "get_connections",
            "get_pipe_connections",
            "get_fluid_segment_contents",
            "get_locked_fluid",
            "get_prototype",
            "get_vehicle_logistic_point",
            "get_personal_logistic_slot",
        )) {
            assertFailsWith<IllegalArgumentException> {
                query(
                    """{"selection":{"kind":"inspect","target":{"kind":"player"},"path":[{"method":"$method"}]}}"""
                )
            }
        }
        assertFailsWith<IllegalArgumentException> {
            query("""{"selection":{"kind":"inspect","target":{"kind":"entities"}}}""")
        }
        assertFailsWith<IllegalArgumentException> {
            query(
                """{"selection":{"kind":"inspect","target":{"kind":"player"},"path":[{"property":"name","index":1}]}}"""
            )
        }
        assertFailsWith<IllegalArgumentException> {
            query(
                """{"selection":{"kind":"inspect","target":{"kind":"player"}},"mode":"members","fields":["name"]}"""
            )
        }
    }

    @Test
    fun lazyStatisticsGettersAreExcludedFromDiscoveryAndSchemas() {
        val methods = listOf(
            "get_item_production_statistics",
            "get_fluid_production_statistics",
            "get_entity_build_count_statistics",
            "get_kill_count_statistics",
            "get_input_count",
            "get_output_count",
            "get_storage_count",
            "get_flow_count",
            "get_current_input_sample",
            "get_current_output_sample",
        )
        val api = RuntimeApi(buildJsonObject {
            put("application", "factorio")
            putJsonArray("classes") {
                addJsonObject {
                    put("name", "LuaForce")
                    putJsonArray("methods") {
                        methods.forEach { method -> addJsonObject { put("name", method) } }
                    }
                }
            }
        })
        assertEquals(JsonArray(emptyList()), api.catalog.getValue("LuaForce").jsonObject["methods"])
        val admitted = inspectionPathSchema().getValue("items").jsonObject
            .getValue("properties").jsonObject.getValue("method").jsonObject.getValue("enum").jsonArray
            .map { it.jsonPrimitive.content }
        assertTrue(methods.none { it in admitted })
    }

    @Test
    fun statisticsDataAttributesAreNotPassiveEvenWhenTheApiDeclaresThemReadable() {
        val api = RuntimeApi(buildJsonObject {
            put("application", "factorio")
            putJsonArray("classes") {
                addJsonObject {
                    put("name", "LuaFlowStatistics")
                    putJsonArray("attributes") {
                        for (name in allocatingStatisticsAttributes + "valid") addJsonObject {
                            put("name", name)
                            put("read_type", "Any")
                        }
                    }
                }
            }
        })
        assertEquals(
            listOf(JsonPrimitive("valid")),
            api.catalog.getValue("LuaFlowStatistics").jsonObject.getValue("attributes").jsonArray.toList()
        )
    }

    @Test
    fun globalPrototypeDiscoveryAndConcreteCatalogValidationAreSeparate() {
        val api = RuntimeApi(
            Json.parseToJsonElement(
                """{"application":"factorio","classes":[
          {"name":"LuaPrototypes","attributes":[{"name":"equipment","read_type":{"complex_type":"LuaCustomTable","key":"string","value":"LuaEquipmentPrototype"}}]},
          {"name":"LuaEquipmentPrototype"}
        ]}"""
            ).jsonObject
        )
        api.prepare(query("""{"selection":{"kind":"inspect","target":{"kind":"prototypes"}},"mode":"members"}"""))
        api.prepare(query("""{"selection":{"kind":"prototypes","type":"equipment"}}"""))
        assertFailsWith<IllegalArgumentException> {
            api.prepare(query("""{"selection":{"kind":"prototypes","type":"missing"}}"""))
        }
    }

    @Test
    fun responsePathsPreserveOriginalArgumentsAndOnlyNormalizeOwnedEnvelopeArrays() {
        val request =
            query("""{"selection":{"kind":"player"},"reads":[{"path":[{"method":"get_inventory","arguments":[1,null]}]}]}""").arguments
        val result = decodeWorldQuery(
            """{"objects":[{"reads":[{"path":[{"method":"get_inventory","arguments":[1]}],"value":{"arguments":{},"reads":{}}}]}]}""",
            request
        )
        val read =
            result.getValue("objects").jsonArray.single().jsonObject.getValue("reads").jsonArray.single().jsonObject
        val step = read.getValue("path").jsonArray.single().jsonObject
        assertEquals(Json.parseToJsonElement("[1,null]"), step["arguments"])
        assertFalse("argument_count" in step)
        assertEquals(Json.parseToJsonElement("""{"arguments":{},"reads":{}}"""), read["value"])
    }

    @Test
    fun surfaceInspectionValidatesSelectorsBeforeAdmission() {
        for (surface in
        listOf("0", "null", "true", "\"\"", "\"bad\\u0000name\"")) {
            assertFailsWith<IllegalArgumentException> {
                query(
                    """{"selection":{"kind":"inspect","target":{"kind":"surface"}},"surface":$surface}"""
                )
            }
        }
        assertFailsWith<IllegalArgumentException> {
            query("""{"selection":{"kind":"inspect","target":{"kind":"surface","name":""}}}""")
        }
        query("""{"selection":{"kind":"inspect","target":{"kind":"surface","name":" "}}}""")
        query("""{"selection":{"kind":"inspect","target":{"kind":"surface"}},"surface":" "}""")
        query("""{"selection":{"kind":"inspect","target":{"kind":"surface"}},"surface":2}""")
        query("""{"selection":{"kind":"inspect","target":{"kind":"surface"}},"surface":"${"x".repeat(1024)}"}""")
    }

    @Test
    fun metadataSuppliesInheritedReadableMembersWithoutAuthorizingEveryMethod() {
        val api =
            RuntimeApi(
                Json.parseToJsonElement(
                    """{
          "application":"factorio","classes":[
            {"name":"LuaBase","attributes":[{"name":"name","read_type":"string"},{"name":"write_only","write_type":"string"}],"methods":[]},
            {"name":"LuaEntity","parent":"LuaBase","attributes":[{"name":"health","read_type":"double"}],"methods":[{"name":"get_recipe","parameters":[]},{"name":"destroy","parameters":[]}],"operators":[]}
          ]
        }"""
                )
                    .jsonObject
            )
        val record = api.catalog.getValue("LuaEntity").jsonObject
        assertEquals(
            listOf("health", "name"),
            record.getValue("attributes").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf("get_recipe"),
            record.getValue("methods").jsonArray.map { it.jsonPrimitive.content },
        )
        val request =
            query(
                """{"selection":{"kind":"inspect","target":{"kind":"player"}},"mode":"members"}"""
            )
        assertTrue("api" in api.prepare(request).arguments)
        assertFalse("api" in request.arguments)
        val empty =
            decodeWorldQuery(
                """{"observation":"object_inspection","objects":[{"object_name":"LuaEntity","members":{}}],"path":{}}"""
            )
        assertEquals(
            JsonArray(emptyList()),
            api.annotate(empty).getValue("objects").jsonArray[0].jsonObject["members"],
        )
    }

    @Test
    fun overviewDetailsReuseEntityIncludesAndRejectLossyAggregation() {
        val args =
            Json.parseToJsonElement(
                """{"detail":"entities","include":["recipe","fluids","filters"],"fields":["position","name"]}"""
            )
                .jsonObject
        val value = parseWorldOverview(args).arguments
        assertEquals(args["include"], value["include"])
        assertEquals(args["fields"], value["fields"])
        assertFailsWith<IllegalArgumentException> {
            parseWorldOverview(Json.parseToJsonElement("""{"include":["recipe"]}""").jsonObject)
        }
        assertFailsWith<IllegalArgumentException> {
            parseWorldOverview(
                Json.parseToJsonElement("""{"detail":"entities","cell_size":2}""").jsonObject
            )
        }
        assertFailsWith<IllegalArgumentException> {
            query("""{"selection":{"kind":"player"},"include":["recipe"]}""")
        }
    }
}
