package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*
import kotlin.test.*

class WorldQuerySchemaTest {
    private val schema = buildJsonObject {
        put("type", "object")
        put("properties", worldQuerySchema())
        putJsonArray("required") { add("query") }
        put("\$defs", worldQueryDefinitions())
    }

    private fun json(text: String) = Json.parseToJsonElement(text)

    private fun matches(value: JsonElement, rule: JsonObject): Boolean {
        rule["\$ref"]?.jsonPrimitive?.content?.let { reference ->
            var target: JsonElement = schema
            for (part in reference.removePrefix("#/").split('/')) target = target.jsonObject.getValue(part)
            if (!matches(value, target.jsonObject)) return false
        }
        rule["const"]?.let { if (value != it) return false }
        rule["enum"]?.jsonArray?.let { if (value !in it) return false }
        rule["type"]?.let { type ->
            val types =
                if (type is JsonArray) type.map { it.jsonPrimitive.content } else listOf(type.jsonPrimitive.content)
            if (types.none {
                    when (it) {
                        "object" -> value is JsonObject
                        "array" -> value is JsonArray
                        "string" -> value is JsonPrimitive && value.isString
                        "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
                        "integer" -> value is JsonPrimitive && !value.isString && value.doubleOrNull?.let { n -> n % 1 == 0.0 } == true
                        "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
                        else -> error("Unexpected schema type $it")
                    }
                }) return false
        }
        if (value is JsonPrimitive && value.isString) {
            rule["minLength"]?.jsonPrimitive?.int?.let { if (value.content.length < it) return false }
            rule["pattern"]?.jsonPrimitive?.content?.let { if (!Regex(it).containsMatchIn(value.content)) return false }
        }
        if (value is JsonPrimitive && !value.isString) value.doubleOrNull?.let { number ->
            rule["minimum"]?.jsonPrimitive?.double?.let { if (number < it) return false }
            rule["maximum"]?.jsonPrimitive?.double?.let { if (number > it) return false }
        }
        if (value is JsonObject) {
            rule["required"]?.jsonArray?.let { if (it.any { key -> key.jsonPrimitive.content !in value }) return false }
            val properties = rule["properties"]?.jsonObject.orEmpty()
            if (rule["additionalProperties"] == JsonPrimitive(false) && value.keys.any { it !in properties }) return false
            if (properties.any { (key, child) ->
                    key in value && !matches(
                        value.getValue(key),
                        child.jsonObject
                    )
                }) return false
        }
        if (value is JsonArray) {
            rule["minItems"]?.jsonPrimitive?.int?.let { if (value.size < it) return false }
            rule["maxItems"]?.jsonPrimitive?.int?.let { if (value.size > it) return false }
            if (rule["uniqueItems"] == JsonPrimitive(true) && value.distinct().size != value.size) return false
            rule["items"]?.jsonObject?.let { if (value.any { item -> !matches(item, it) }) return false }
        }
        rule["allOf"]?.jsonArray?.let { if (it.any { child -> !matches(value, child.jsonObject) }) return false }
        rule["anyOf"]?.jsonArray?.let { if (it.none { child -> matches(value, child.jsonObject) }) return false }
        rule["oneOf"]?.jsonArray?.let { if (it.count { child -> matches(value, child.jsonObject) } != 1) return false }
        rule["not"]?.jsonObject?.let { if (matches(value, it)) return false }
        rule["if"]?.jsonObject?.let { test ->
            val selected = if (matches(value, test)) rule["then"] else rule["else"]
            if (selected != null && !matches(value, selected.jsonObject)) return false
        }
        return true
    }

    @Test
    fun validBranchCombinationsMatchTheAdvertisedSchemaAndAdmission() {
        for (text in listOf(
            """{"selection":{"kind":"entities","position":{"x":0,"y":0}},"fields":[],"reads":[{"path":[{"method":"get_recipe"}],"fields":["name"]}]}""",
            """{"selection":{"kind":"entities","position":{"x":0,"y":0},"unit_number":3},"fields":["crafting_speed"]}""",
            """{"selection":{"kind":"force","force":"enemy"},"fields":["name"]}""",
            """{"selection":{"kind":"players"},"fields":[],"limit":1000}""",
            """{"selection":{"kind":"prototypes","type":"equipment"},"fields":["name"]}""",
            """{"selection":{"kind":"recipes","force":1,"product":{"type":"item","name":"iron-plate"}}}""",
            """{"selection":{"kind":"inventory","owner":{"kind":"entities","unit_number":7}},"surface":"other","fields":[],"reads":[{"path":[{"method":"get_inventory","arguments":[1]},{"method":"get_contents"}]}]}""",
            """{"selection":{"kind":"inspect","target":{"kind":"defines"}},"depth":20}""",
            """{"selection":{"kind":"inspect","target":{"kind":"prototypes"}},"mode":"members","search":"equipment"}""",
            """{"selection":{"kind":"inspect","target":{"kind":"game"},"path":[{"property":"surfaces"}]},"mode":"entries","reads":[{"fields":["name"]}]}""",
            """{"selection":{"kind":"quickbar","screen_pages":[1]}}""",
        )) {
            val query = json(text).jsonObject
            assertTrue(matches(buildJsonObject { put("query", query) }, schema), text)
            parseWorldQuery(query)
        }
    }

    @Test
    fun collectionControlsAndPathsAreRejectedInTheWrongBranch() {
        for (text in listOf(
            """{"selection":{"kind":"player"},"limit":1}""",
            """{"selection":{"kind":"entities","unit_number":7},"limit":1}""",
            """{"selection":{"kind":"tiles","position":{"x":0,"y":0}},"limit":1}""",
            """{"selection":{"kind":"entities","position":{"x":0,"y":0}},"offset":0}""",
            """{"selection":{"kind":"inspect","target":{"kind":"player"}},"limit":1}""",
            """{"selection":{"kind":"inspect","target":{"kind":"game"}},"mode":"entries","fields":["name"]}""",
            """{"selection":{"kind":"inspect","target":{"kind":"game"}},"mode":"slice","depth":1}""",
            """{"selection":{"kind":"inspect","target":{"kind":"game"}},"mode":"members","reads":[{}]}""",
            """{"selection":{"kind":"prototypes","type":"equipment","product":{"type":"item","name":"x"}}}""",
            """{"selection":{"kind":"inventory"},"surface":1}""",
            """{"selection":{"kind":"entities","position":{"x":0,"y":0},"filters":{"limit":1}}}""",
            """{"selection":{"kind":"player"},"reads":[{"path":[{"property":"name","arguments":[]}]}]}""",
            """{"selection":{"kind":"player"},"reads":[{"path":[{"method":"get_item_production_statistics"}]}]}""",
        )) {
            val query = json(text).jsonObject
            assertFalse(matches(buildJsonObject { put("query", query) }, schema), text)
            assertFailsWith<IllegalArgumentException>(text) { parseWorldQuery(query) }
        }
    }

    @Test
    fun methodAdmissionUsesTheDeclaredInterfaceAndUnknownConcreteNamesStayOriginal() {
        val api = RuntimeApi(
            json(
                """{"application":"factorio","classes":[
          {"name":"LuaPlayer","attributes":[{"name":"statistics","read_type":"LuaFlowStatistics"}]},
          {"name":"LuaFlowStatistics","attributes":[{"name":"valid","read_type":"boolean"}]},
          {"name":"LuaSurface","methods":[{"name":"get_records"},{"name":"get_chunks"}]},
          {"name":"LuaSchedule","methods":[{"name":"get_records"}]}
        ]}"""
            ).jsonObject
        )
        assertEquals(
            "LuaFlowStatistics", api.catalog.getValue("LuaPlayer").jsonObject
                .getValue("attribute_types").jsonObject.getValue("statistics").jsonObject.getValue("class").jsonPrimitive.content
        )
        assertEquals(
            listOf("get_chunks"),
            api.catalog.getValue("LuaSurface").jsonObject.getValue("methods").jsonArray.map { it.jsonPrimitive.content })
        val annotated =
            api.annotate(json("""{"objects":[{"reads":[{"object_name":"NativeStatistics","api_class":"LuaFlowStatistics","members":[{"name":"valid","kind":"attribute"}]}]}]}""").jsonObject)
        val observation =
            annotated.getValue("objects").jsonArray[0].jsonObject.getValue("reads").jsonArray[0].jsonObject
        assertEquals("NativeStatistics", observation.getValue("object_name").jsonPrimitive.content)
        assertTrue("api" in observation.getValue("members").jsonArray[0].jsonObject)
    }
}
