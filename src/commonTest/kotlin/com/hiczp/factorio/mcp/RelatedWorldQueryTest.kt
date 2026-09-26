package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RelatedWorldQueryTest {
    private fun query(text: String) =
        parseWorldQuery(Json.parseToJsonElement(text).jsonObject).arguments

    @Test
    fun inventoryAndCatalogDefaultsKeepTheirDistinctContracts() {
        val inventory = query("""{"selection":{"kind":"inventory"},"offset":7}""")
        assertEquals(7, inventory["offset"]!!.jsonPrimitive.int)
        assertEquals(64, inventory["limit"]!!.jsonPrimitive.int)
        assertTrue(inventory["fields"]!!.jsonArray.contains(JsonPrimitive("quality")))
        val discovery =
            query(
                """{"selection":{"kind":"inventories","owner":{"kind":"entities","unit_number":7}}}"""
            )
        assertTrue(discovery["fields"]!!.jsonArray.isEmpty())
        val recipe =
            query("""{"selection":{"kind":"recipes","names":["iron-gear-wheel","copper-cable"]}}""")
        assertTrue(recipe["fields"]!!.jsonArray.contains(JsonPrimitive("enabled")))
        val prototype =
            query("""{"selection":{"kind":"prototypes","type":"item","search":"plate"}}""")
        assertTrue(prototype["fields"]!!.jsonArray.contains(JsonPrimitive("place_result")))
    }

    @Test
    fun unboundedCatalogsRecursiveOwnersAndWrongFieldFamiliesAreRejected() {
        for (text in
        listOf(
            """{"selection":{"kind":"prototypes","type":"unknown"}}""",
            """{"selection":{"kind":"recipes","type":"recipe"}}""",
            """{"selection":{"kind":"recipes"},"surface":1}""",
            """{"selection":{"kind":"recipes","names":[]}}""",
            """{"selection":{"kind":"recipes","names":["a","a"]}}""",
            """{"selection":{"kind":"recipes","search":""}}""",
            """{"selection":{"kind":"recipes"},"limit":129}""",
            """{"selection":{"kind":"inventory"},"limit":513}""",
            """{"selection":{"kind":"inventory"},"offset":-1}""",
            """{"selection":{"kind":"inventory"},"surface":1}""",
            """{"selection":{"kind":"inventory","owner":{"kind":"inventory"}}}""",
            """{"selection":{"kind":"inventory","owner":{"kind":"entities"}}}""",
            """{"selection":{"kind":"inventories"},"fields":["name"]}""",
            """{"selection":{"kind":"inventories","inventory":"fuel"}}""",
            """{"selection":{"kind":"inventory","inventory":"bad\u0000name"}}""",
            """{"selection":{"kind":"inventory"},"fields":["can_insert"]}""",
            """{"selection":{"kind":"recipes"},"fields":["stack_size"]}""",
            """{"selection":{"kind":"player"},"offset":0}""",
        )) assertFailsWith<IllegalArgumentException>(text) { query(text) }
    }

    @Test
    fun recipeRelationFiltersDoNotAcceptOtherObjectFamiliesOrLooseIdentities() {
        query(
            """{"selection":{"kind":"recipes","product":{"type":"item","name":"iron-plate"},"ingredient":{"type":"fluid","name":"water"}}}"""
        )
        query(
            """{"selection":{"kind":"prototypes","type":"recipe","product":{"type":"item","name":"iron-plate"}}}"""
        )
        for (request in
        listOf(
            """{"selection":{"kind":"technologies","product":{"type":"item","name":"a"}}}""",
            """{"selection":{"kind":"recipes","product":{"name":"a"}}}""",
            """{"selection":{"kind":"recipes","ingredient":{"type":"entity","name":"a"}}}""",
            """{"selection":{"kind":"recipes","product":{"type":"item","name":""}}}""",
            """{"selection":{"kind":"prototypes","type":"item","product":{"type":"item","name":"a"}}}""",
        )) assertFailsWith<IllegalArgumentException> { query(request) }
    }

    @Test
    fun localOwnersTechnologiesAndGroupsReuseBoundedQueries() {
        for (kind in localEntityKinds) query(
            """{"selection":{"kind":"inventory","owner":{"kind":"$kind"}},"fields":["ammo","durability"]}"""
        )
        val technology = query("""{"selection":{"kind":"technologies","names":["automation"]}}""")
        assertTrue(technology["fields"]!!.jsonArray.contains(JsonPrimitive("researched")))
        query(
            """{"selection":{"kind":"prototypes","type":"item_subgroup"},"fields":["name","group"]}"""
        )
        for (request in
        listOf(
            """{"selection":{"kind":"technologies","type":"technology"}}""",
            """{"selection":{"kind":"technologies"},"fields":["ingredients"]}""",
            """{"selection":{"kind":"inventory","owner":{"kind":"force"}}}""",
            """{"selection":{"kind":"inventory","owner":{"kind":"character"}},"surface":1}""",
        )) assertFailsWith<IllegalArgumentException> { query(request) }
    }

    @Test
    fun quickbarUsesExplicitBoundedIndicesWithoutInventingPageSizes() {
        val result =
            query("""{"selection":{"kind":"quickbar","slots":[1,100,101],"screen_pages":[1,4]}}""")
        assertEquals(3, result["limit"]!!.jsonPrimitive.int)
        assertTrue(result["fields"]!!.jsonArray.isEmpty())
        assertEquals(
            0,
            query("""{"selection":{"kind":"quickbar","screen_pages":[1]}}""")["limit"]!!
                .jsonPrimitive
                .int,
        )
        for (request in
        listOf(
            """{"selection":{"kind":"quickbar"}}""",
            """{"selection":{"kind":"quickbar","slots":[]}}""",
            """{"selection":{"kind":"quickbar","slots":[1,1]}}""",
            """{"selection":{"kind":"quickbar","slots":[0]}}""",
            """{"selection":{"kind":"quickbar","slots":["1"]}}""",
            """{"selection":{"kind":"quickbar","screen_pages":[65537]}}""",
            """{"selection":{"kind":"quickbar","slots":[1]},"limit":1}""",
            """{"selection":{"kind":"quickbar","slots":[1]},"fields":["filter"]}""",
            """{"selection":{"kind":"quickbar","slots":[1]},"offset":0}""",
        )) assertFailsWith<IllegalArgumentException> { query(request) }
        val excessive = buildJsonObject {
            putJsonObject("selection") {
                put("kind", "quickbar")
                putJsonArray("slots") { (1..129).forEach { add(it) } }
            }
        }
        assertFailsWith<IllegalArgumentException> { parseWorldQuery(excessive) }
    }

    @Test
    fun emptyRecipeArraysAndMissingNamesAreNormalizedWithoutChangingDictionaries() {
        val result =
            decodeWorldQuery(
                """{"missing_names":{},"objects":[{"attributes":{"ingredients":{},"products":{},"prerequisites":{},"crafting_categories":{}},"read_status":{}}]}"""
            )
        assertEquals(JsonArray(emptyList()), result["missing_names"])
        val attributes =
            result["objects"]!!.jsonArray.single().jsonObject["attributes"]!!.jsonObject
        assertEquals(JsonArray(emptyList()), attributes["ingredients"])
        assertEquals(JsonArray(emptyList()), attributes["products"])
        assertEquals(JsonObject(emptyMap()), attributes["prerequisites"])
        assertEquals(JsonObject(emptyMap()), attributes["crafting_categories"])
        val force =
            decodeWorldQuery(
                """{"objects":[{"attributes":{"research_queue":{},"subgroups":{}}}],"active_pages":{}}"""
            )
        assertEquals(JsonArray(emptyList()), force["active_pages"])
        assertEquals(
            JsonArray(emptyList()),
            force["objects"]!!
                .jsonArray
                .single()
                .jsonObject["attributes"]!!
                .jsonObject["research_queue"],
        )
    }
}
