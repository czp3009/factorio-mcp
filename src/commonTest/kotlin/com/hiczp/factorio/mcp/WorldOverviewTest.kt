package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.*

class WorldOverviewTest {
    private fun parse(text: String) = parseWorldOverview(Json.parseToJsonElement(text).jsonObject)

    @Test
    fun viewportIsDefaultAndExplicitAreaPreservesGranularity() {
        val viewport = parse("{}")
        assertTrue(viewport.includeViewport)
        assertEquals(
            "overview",
            viewport.arguments["selection"]!!.jsonObject["kind"]!!.jsonPrimitive.content,
        )
        assertEquals(64, viewport.arguments["limit"]!!.jsonPrimitive.int)
        val area =
            parse(
                """{"area":{"left_top":{"x":-4,"y":-2},"right_bottom":{"x":4,"y":6}},"cell_size":2,"entity_limit":12,"surface":1}"""
            )
        assertEquals(2, area.arguments["cell_size"]!!.jsonPrimitive.int)
        assertEquals(12, area.arguments["limit"]!!.jsonPrimitive.int)
        assertEquals(1, area.arguments["surface"]!!.jsonPrimitive.int)
        val empty =
            decodeWorldQuery("""{"objects":[{"entity_groups":{},"coverage":{}}]}""")["objects"]!!
                .jsonArray
                .single()
                .jsonObject
        assertEquals(JsonArray(emptyList()), empty["entity_groups"])
        assertEquals(JsonObject(emptyMap()), empty["coverage"])
    }

    @Test
    fun entityTypeUnionsPreserveNativeFiltersInBothModes() {
        for (detail in listOf("grid", "entities")) {
            val value =
                parse(
                    """{"detail":"$detail","type":["transport-belt","mining-drill"],"name":"mod-drill"}"""
                )
            val selector = value.arguments.getValue("selection").jsonObject
            assertEquals(
                listOf("transport-belt", "mining-drill"),
                selector.getValue("type").jsonArray.map { it.jsonPrimitive.content },
            )
            assertEquals("mod-drill", selector.getValue("name").jsonPrimitive.content)
        }
        for (filter in listOf("[]", "[1]", "[\"x\",\"x\"]", "\" \"")) {
            assertFailsWith<IllegalArgumentException> { parse("""{"type":$filter}""") }
        }
    }

    @Test
    fun rejectsAmbiguousUnboundedAndMalformedRequests() {
        for (text in
        listOf(
            """{"surface":1}""",
            """{"cell_size":0}""",
            """{"cell_size":4097}""",
            """{"entity_limit":513}""",
            """{"cell_size":"32"}""",
            """{"unknown":true}""",
            """{"area":{"left_top":{"x":0,"y":0},"right_bottom":{"x":4097,"y":1}}}""",
            """{"area":{"left_top":{"x":0,"y":0},"right_bottom":{"x":0,"y":1}}}""",
            """{"area":{"left_top":{"x":0,"y":0},"right_bottom":{"x":1,"y":1}},"surface":0}""",
        )) assertFailsWith<IllegalArgumentException>(text) { parse(text) }
    }
}
