package com.hiczp.factorio.mcp

import kotlin.test.*
import kotlinx.serialization.json.*

class ObjectInspectionTest {
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
    fun surfaceInspectionValidatesSelectorsBeforeAdmission() {
        for (surface in
        listOf("0", "null", "true", "\" \"", "\"bad\\u0000name\"", "\"${"x".repeat(257)}\"")) {
            assertFailsWith<IllegalArgumentException> {
                query(
                    """{"selection":{"kind":"inspect","target":{"kind":"surface"}},"surface":$surface}"""
                )
            }
        }
        assertFailsWith<IllegalArgumentException> {
            query("""{"selection":{"kind":"inspect","target":{"kind":"surface","name":" "}}}""")
        }
        query("""{"selection":{"kind":"inspect","target":{"kind":"surface"}},"surface":2}""")
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
