package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*
import kotlin.test.*

class UiActionTest {
    @Test
    fun guiPredicatesPreserveOpaqueNestedValuesAndLongStructuralPaths() {
        val selector = Json.parseToJsonElement("""{"path":[{"axis":"descendant","match":{"gui":{"sprite":"item/mod-value","tags":{"enabled":false,"ids":[3,3,4]}}}}]}""")
        val predicate = parseSelector(selector).single().gui!!
        assertEquals(false, predicate.getValue("tags").jsonObject.getValue("enabled").jsonPrimitive.boolean)
        assertEquals(listOf(3, 3, 4), predicate.getValue("tags").jsonObject.getValue("ids").jsonArray.map { it.jsonPrimitive.int })
        val longPath = JsonObject(mapOf("path" to JsonArray(List(256) { selector.jsonObject.getValue("path").jsonArray.single() })))
        assertEquals(256, parseSelector(longPath).size)
        parseSelector(Json.parseToJsonElement("""{"path":[{"axis":"descendant","match":{"gui":{"tags":{"":true,"opaque\u0000key":"value\u0000"}}}}]}"""))
        assertFailsWith<IllegalArgumentException> {
            parseSelector(Json.parseToJsonElement("""{"path":[{"axis":"descendant","match":{"gui":{"sprite":null}}}]}"""))
        }
    }

    @Test
    fun visibilityPredicateRequiresBooleanAndKeepsUnknownDistinct() {
        fun parse(value: String) =
            parseSelector(
                Json.parseToJsonElement("""{"path":[{"axis":"descendant","match":{$value}}]}""")
            )
                .single()
                .visible
        assertNull(parse(""))
        assertEquals(true, parse("\"visible\":true"))
        assertEquals(false, parse("\"visible\":false"))
        for (value in listOf("null", "1", "\"true\"")) {
            assertFailsWith<IllegalArgumentException> { parse("\"visible\":$value") }
        }
    }

    @Test
    fun prototypeSelectorsValidateNamesWithoutGameplaySemantics() {
        val path =
            parseSelector(
                Json.parseToJsonElement(
                    """{"path":[{"axis":"descendant","match":{"prototype":{"name":"iron-plate","native_type":"ItemPrototype"}}}]}"""
                )
            )
        assertEquals(UiPrototypeMatch("iron-plate", "ItemPrototype"), path.single().prototype)
        for (prototype in
        listOf("""{"name":""}""", """{"name":"x","quality":"normal"}""", """{"name":3}""")) {
            assertFailsWith<IllegalArgumentException> {
                parseSelector(
                    buildJsonObject {
                        putJsonArray("path") {
                            addJsonObject {
                                put("axis", "descendant")
                                putJsonObject("match") {
                                    put("prototype", Json.parseToJsonElement(prototype))
                                }
                            }
                        }
                    }
                )
            }
        }
    }

    private fun action(json: String) = parseUiAction(Json.parseToJsonElement(json).jsonObject)

    @Test
    fun selectorAndGestureValidation() {
        val parsed =
            action(
                """{"selector":{"path":[{"axis":"descendant","match":{"text":"Settings"}}]},"action":"click","button":"right","modifiers":["shift"]}"""
            )
        assertEquals("Settings", parsed.path.single().text)
        assertEquals(1, parsed.button)
        assertTrue(parsed.shift)
        assertFailsWith<IllegalArgumentException> {
            action(
                """{"selector":{"path":[{"axis":"descendant","match":{},"position":0}]},"action":"click"}"""
            )
        }
        assertFailsWith<IllegalArgumentException> {
            action(
                """{"selector":{"path":[{"axis":"descendant","match":{}}]},"action":"click","text":"ignored"}"""
            )
        }
    }

    @Test
    fun finiteFrontendKeysValidateIndependentOfSelectors() {
        val tap = action("""{"action":"press_key","key":"TAB"}""")
        assertEquals(3, tap.kind)
        assertTrue(tap.path.isEmpty())
        assertEquals(listOf("TAB"), tap.keys)
        assertEquals(
            listOf("LCTRL", "A"),
            action("""{"action":"press_key","key":"A","modifiers":["control"]}""").keys,
        )
        for (json in
        listOf(
            """{"action":"press_key","key":"TAB","text":"ignored"}""",
            """{"action":"press_key","key":"LCTRL","modifiers":["control"]}""",
            """{"action":"press_key","key":"TAB","modifiers":["shift","shift"]}""",
            """{"action":"press_key","key":43}""",
        )) assertFailsWith<IllegalArgumentException> { action(json) }
    }

    @Test
    fun unicodeTextAndBounds() {
        assertEquals(listOf(65, 0x1F680, 0x4E2D), unicodeScalars("A🚀中"))
        assertEquals(emptyList(), unicodeScalars(""))
        assertFailsWith<IllegalArgumentException> { unicodeScalars("\uD800") }
        assertFailsWith<IllegalArgumentException> { unicodeScalars("\u0000") }
        assertEquals(4096, unicodeScalars("a".repeat(4096)).size)
        assertFailsWith<IllegalArgumentException> { unicodeScalars("a".repeat(16385)) }
    }
}
