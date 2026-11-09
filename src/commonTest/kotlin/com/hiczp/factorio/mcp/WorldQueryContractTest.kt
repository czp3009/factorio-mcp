package com.hiczp.factorio.mcp

import kotlin.test.*
import kotlinx.serialization.json.*

class WorldQueryContractTest {
    private fun query(value: String) = parseWorldQuery(Json.parseToJsonElement(value).jsonObject).arguments

    @Test
    fun singleSelectionsRejectIneffectiveCountsAndSpatialQueriesRetainExplicitBounds() {
        for (selection in listOf(
            """{"kind":"player"}""", """{"kind":"force"}""", """{"kind":"character"}""",
            """{"kind":"vehicle"}""", """{"kind":"physical_vehicle"}""",
            """{"kind":"entities","unit_number":7}""", """{"kind":"tiles","position":{"x":0,"y":0}}""",
        )) {
            assertNull(query("""{"selection":$selection}""")["limit"])
            assertFails { query("""{"selection":$selection,"limit":1}""") }
        }
        val selection = """{"kind":"entities","area":{"left_top":{"x":0,"y":0},"right_bottom":{"x":32,"y":32}}}"""
        assertEquals(128, query("""{"selection":$selection}""").getValue("limit").intArgument())
        assertEquals(100000, query("""{"selection":$selection,"limit":100000}""").getValue("limit").intArgument())
        assertFails { query("""{"selection":$selection,"offset":0}""") }
        assertNull(worldQuerySchema()["start_position"])
    }

    @Test
    fun valuesAreCompleteSelectionsWhileOnlyExplicitCollectionModesHavePageArguments() {
        val selection = """{"kind":"inspect","target":{"kind":"player"}}"""
        val request = query("""{"selection":$selection}""")
        assertNull(request["limit"])
        assertNull(request["offset"])
        for (argument in listOf("\"limit\":100000", "\"offset\":0")) {
            assertFails { query("""{"selection":$selection,$argument}""") }
        }
        assertNull(query("""{"selection":$selection,"mode":"members"}""")["limit"])
        assertEquals(64, query("""{"selection":$selection,"mode":"entries"}""").getValue("limit").intArgument())
        assertEquals(4096, query("""{"selection":$selection,"mode":"slice"}""").getValue("limit").intArgument())
        assertFails { query("""{"selection":$selection,"mode":"members","depth":2}""") }
        assertFails { query("""{"selection":$selection,"mode":"slice","depth":2}""") }
    }

    @Test
    fun depthGuardsAndOpaqueTableKeysAreSharedAcrossValueReads() {
        for (selection in listOf("""{"kind":"player"}""", """{"kind":"players"}""", """{"kind":"inventory"}""")) {
            assertEquals(MAX_OBSERVATION_DEPTH,
                query("""{"selection":$selection,"depth":2147483647}""").getValue("depth").intArgument())
            assertFails { query("""{"selection":$selection,"depth":-1}""") }
        }
        for (key in listOf("", "a\u0000b")) {
            val value = buildJsonObject {
                putJsonObject("selection") {
                    put("kind", "inspect")
                    putJsonObject("target") { put("kind", "player") }
                    putJsonArray("path") { addJsonObject { put("index", key) } }
                }
            }
            val path = parseWorldQuery(value).arguments.getValue("inspection").jsonObject.getValue("path").jsonArray
            assertEquals(key, path.single().jsonObject.getValue("index").stringArgument())
        }
    }

    @Test
    fun arrayDepthMarkersRemainValuesAndSpatialResultsDoNotInventPages() {
        val marker = """{"observation":"reference","value_type":"table","reason":"depth_bound","children_truncated":true}"""
        val result = decodeWorldQuery("""{"selection":{"kind":"entities"},"selection_complete":true,"truncated":true,"depth_truncated":true,"objects":[{"attributes":{"products":$marker}}]}""")
        assertNull(result["has_more"])
        assertEquals(Json.parseToJsonElement(marker), result.getValue("objects").jsonArray.single().jsonObject
            .getValue("attributes").jsonObject["products"])
    }
}
