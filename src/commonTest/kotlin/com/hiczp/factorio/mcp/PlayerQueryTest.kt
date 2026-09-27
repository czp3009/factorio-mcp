package com.hiczp.factorio.mcp

import kotlin.test.*
import kotlinx.serialization.json.*

class PlayerQueryTest {
    private fun query(text: String) =
        parseWorldQuery(Json.parseToJsonElement(text).jsonObject).arguments

    @Test
    fun otherPlayersCanBeSelectedWithoutChangingTheLocalContext() {
        for (kind in listOf("player", "character", "vehicle", "physical_vehicle", "force")) {
            val value = query("""{"selection":{"kind":"$kind","player":7}}""")
            assertEquals(
                7,
                value.getValue("selection").jsonObject.getValue("player").jsonPrimitive.int,
            )
        }
        val inventory =
            query(
                """{"selection":{"kind":"inventory","owner":{"kind":"character","player":"Engineer"}}}"""
            )
        assertEquals(
            "Engineer",
            inventory
                .getValue("selection")
                .jsonObject
                .getValue("owner")
                .jsonObject
                .getValue("player")
                .jsonPrimitive
                .content,
        )
        val players =
            query("""{"selection":{"kind":"players","connected":true},"offset":4,"limit":3}""")
        assertEquals(4, players.getValue("offset").jsonPrimitive.int)
        assertTrue(JsonPrimitive("physical_surface") in players.getValue("fields").jsonArray)
    }

    @Test
    fun filtersCannotBeMistakenForOtherSelectors() {
        for (text in
        listOf(
            """{"selection":{"kind":"player","player":0}}""",
            """{"selection":{"kind":"player","player":""}}""",
            """{"selection":{"kind":"entities","unit_number":1,"player":1}}""",
            """{"selection":{"kind":"players","indices":[1,1]}}""",
            """{"selection":{"kind":"players","names":[1]}}""",
            """{"selection":{"kind":"players","connected":"true"}}""",
            """{"selection":{"kind":"players"},"fields":["health"]}""",
        )) assertFailsWith<IllegalArgumentException>(text) { query(text) }
    }
}
