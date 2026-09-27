package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.*

class WorldQueryTest {
    private fun query(value: String) =
        parseWorldQuery(Json.parseToJsonElement(value).jsonObject).arguments

    @Test
    fun defaultsAndSelectionsPreserveApiSemantics() {
        val player = query("""{"selection":{"kind":"player"}}""")
        assertEquals(128, player["limit"]!!.jsonPrimitive.int)
        assertTrue(player["fields"]!!.jsonArray.any { it.jsonPrimitive.content == "cursor_stack" })
        val unit = query("""{"selection":{"kind":"entities","unit_number":4294967295}}""")
        assertEquals(4294967295, unit["selection"]!!.jsonObject["unit_number"]!!.jsonPrimitive.long)
        val spatialUnit =
            query(
                """{"selection":{"kind":"entities","unit_number":7,"position":{"x":6.5,"y":0.5}}}"""
            )
        assertEquals(7, spatialUnit["selection"]!!.jsonObject["unit_number"]!!.jsonPrimitive.int)
        val tile =
            query(
                """{"selection":{"kind":"tiles","position":{"x":-0.1,"y":2.5}},"fields":["hidden_tile"]}"""
            )
        assertEquals(
            -0.1,
            tile["selection"]!!.jsonObject["position"]!!.jsonObject["x"]!!.jsonPrimitive.double,
        )
    }

    @Test
    fun spatialSelectionsPreserveCombinedNativeFilters() {
        for (location in
        listOf(
            "\"area\":{\"left_top\":{\"x\":0,\"y\":0},\"right_bottom\":{\"x\":32,\"y\":32}}",
            "\"position\":{\"x\":0,\"y\":0},\"radius\":32",
        )) {
            val value =
                query(
                    """{"selection":{"kind":"entities",$location,"type":["transport-belt","mining-drill"],"name":["mod-belt","mod-drill"]}}"""
                )
                    .getValue("selection")
                    .jsonObject
            assertEquals(
                listOf("transport-belt", "mining-drill"),
                value.getValue("type").jsonArray.map { it.jsonPrimitive.content },
            )
            assertEquals(
                listOf("mod-belt", "mod-drill"),
                value.getValue("name").jsonArray.map { it.jsonPrimitive.content },
            )
        }
        for (key in listOf("name", "type")) {
            for (filter in listOf("[]", "[1]", "[\"x\",\"x\"]", "\" \"", "null")) {
                assertFailsWith<IllegalArgumentException> {
                    query(
                        """{"selection":{"kind":"entities","position":{"x":0,"y":0},"$key":$filter}}"""
                    )
                }
            }
        }
    }

    @Test
    fun localReferencesHaveNoSpatialArgumentsAndKeepFieldFamiliesDistinct() {
        for (kind in localEntityKinds) {
            val value =
                query(
                    """{"selection":{"kind":"$kind"},"fields":["health","max_health","speed","selected_gun_index"]}"""
                )
            assertEquals(kind, value["selection"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
            assertFailsWith<IllegalArgumentException> {
                query("""{"selection":{"kind":"$kind"},"surface":1}""")
            }
            assertFailsWith<IllegalArgumentException> {
                query("""{"selection":{"kind":"$kind","unit_number":1}}""")
            }
        }
        assertTrue(
            query("""{"selection":{"kind":"force"}}""")["fields"]!!
                .jsonArray
                .contains(JsonPrimitive("current_research"))
        )
        assertFailsWith<IllegalArgumentException> {
            query("""{"selection":{"kind":"force"},"fields":["health"]}""")
        }
        query(
            """{"selection":{"kind":"player"},"fields":["physical_controller_type","vehicle","physical_vehicle","driving"]}"""
        )
    }

    @Test
    fun emptyLuaCollectionsRetainTheirPublicArrayType() {
        assertEquals(JsonArray(emptyList()), decodeWorldQuery("""{"objects":{}}""")["objects"])
        val result =
            decodeWorldQuery(
                """{"objects":[{"attributes":{"crafting_queue":{}},"read_status":{}}]}"""
            )
        val item = result.getValue("objects").jsonArray.single().jsonObject
        assertEquals(
            JsonArray(emptyList()),
            item.getValue("attributes").jsonObject["crafting_queue"],
        )
        assertEquals(JsonObject(emptyMap()), item["read_status"])
        assertFailsWith<IllegalArgumentException> {
            decodeWorldQuery("""{"objects":{"unexpected":1}}""")
        }
    }

    @Test
    fun rejectsUnboundedAmbiguousAndUnsupportedRequestsBeforeAdmission() {
        for (request in
        listOf(
            """{"selection":{"kind":"entities"}}""",
            """{"selection":{"kind":"player"},"surface":1}""",
            """{"selection":{"kind":"tiles","position":{"x":0,"y":0},"radius":1}}""",
            """{"selection":{"kind":"entities","unit_number":1,"name":"iron-ore"}}""",
            """{"selection":{"kind":"entities","unit_number":4294967296}}""",
            """{"selection":{"kind":"tiles","position":{"x":0,"y":0},"unit_number":1}}""",
            """{"selection":{"kind":"entities","area":{"left_top":{"x":0,"y":0},"right_bottom":{"x":129,"y":1}}}}""",
            """{"selection":{"kind":"entities","area":{"left_top":{"x":0,"y":0},"right_bottom":{"x":0,"y":1}}}}""",
            """{"selection":{"kind":"player"},"fields":["request_translation"]}""",
            """{"selection":{"kind":"player"},"limit":"2"}""",
            """{"selection":{"kind":"player"},"limit":513}""",
            """{"selection":{"kind":"player"},"fields":["name","name"]}""",
            """{"selection":{"kind":"tiles","position":{"x":0,"y":0}},"surface":"bad\u0000name"}""",
        )) assertFailsWith<IllegalArgumentException>(request) { query(request) }
    }
}
