package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JsonArgumentsTest {
    @Test
    fun doesNotCoerceQuotedNumbersOrBooleans() {
        assertEquals(42, JsonPrimitive(42).intArgument())
        assertTrue(JsonPrimitive(true).booleanArgument())
        assertEquals(0.5, JsonPrimitive(0.5).doubleArgument())
        assertFailsWith<IllegalArgumentException> { JsonPrimitive("42").intArgument() }
        assertFailsWith<IllegalArgumentException> { JsonPrimitive("true").booleanArgument() }
        assertFailsWith<IllegalArgumentException> { JsonPrimitive("0.5").doubleArgument() }
        assertFailsWith<IllegalArgumentException> { JsonNull.intArgument() }
        assertFailsWith<IllegalArgumentException> { JsonPrimitive(1).stringArgument() }
        assertFailsWith<IllegalArgumentException> {
            parseSelector(
                buildJsonObject {
                    putJsonArray("path") {
                        addJsonObject {
                            put("axis", "descendant")
                            putJsonObject("match") { put("native_type", "") }
                        }
                    }
                }
            )
        }
    }
}
