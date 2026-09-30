package com.hiczp.factorio.mcp

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WidgetTextTest {
    private fun node(text: String?) = WidgetSnapshot(0, "agui::Widget", text, true, 0, 0, 1, 1, false)

    @Test
    fun observedEmptyTextRetainsItsExistingProjection() {
        assertTrue(buildJsonObject { widgetText(node("")) }.isEmpty())
    }

    @Test
    fun unsupportedTextIsExplicitlyUnknown() {
        val value = buildJsonObject {
            widgetText(node(null).copy(textUnavailableReason = "Unsupported native text accessor"))
        }
        assertEquals(JsonNull, value["text"])
        assertEquals(
            "Unsupported native text accessor",
            value.getValue("text_unavailable_reason").jsonPrimitive.content
        )
    }

    @Test
    fun preservesNativeTextAndItsOwnTruncationFlag() {
        val native = "[font=default]Text\u0000with a zero byte"
        val value = buildJsonObject { widgetText(node(native).copy(textTruncated = true)) }
        assertEquals(native, value.getValue("text").jsonPrimitive.content)
        assertEquals("true", value.getValue("text_truncated").jsonPrimitive.content)
    }
}
