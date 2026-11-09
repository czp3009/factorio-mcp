package com.hiczp.factorio.mcp

import kotlin.test.*
import kotlinx.serialization.json.*

class UiMediaTest {
    @Test
    fun distinguishesAbsentEmptyStaticAndOpaqueImagesWithoutExpandingMedia() {
        fun unused(): String = error("Unobserved media must not be read")
        assertNull(widgetImage(0u, 0u, 0, 0u, ::unused, ::unused))
        assertEquals(JsonNull, widgetImage(1u, 0u, 0, 0u, ::unused, ::unused)!!.json())
        val animation = widgetImage(2u, 0u, 0, 0u, { "class GuiAnimation" }, ::unused)!!.json().jsonObject
        assertEquals(buildJsonObject { put("native_type", "GuiAnimation") }, animation)
        val unknown = widgetImage(2u, 0u, 0, 0u, { "opaque::Image" }, ::unused)!!.json().jsonObject
        assertEquals(JsonPrimitive("opaque::Image"), unknown["native_type"])
        val empty = widgetImage(2u, 1u, -1, 0u, { "GuiImage" }, ::unused)!!.json().jsonObject
        assertEquals(JsonNull, empty["sprite"])
        val image = widgetImage(2u, 1u, 7, 0u, { "GuiImage" }, { "original_registered_name" })!!.json().jsonObject
        assertEquals(JsonPrimitive("original_registered_name"), image.getValue("sprite").jsonObject["name"])
        assertEquals(JsonPrimitive(7), image.getValue("sprite").jsonObject["reference"])
        assertEquals(JsonPrimitive("snapshot"), image.getValue("sprite").jsonObject["identity_scope"])
        assertFails { widgetImage(3u, 0u, 0, 0u, ::unused, ::unused) }
        assertFails { widgetImage(1u, 1u, 0, 0u, ::unused, ::unused) }
        assertFails { widgetImage(2u, 0u, 0, 1u, ::unused, ::unused) }
    }

    @Test
    fun projectsImageMetadataIndependentlyAndReportsUnavailableMetadata() {
        val snapshot = GameSnapshot("in_game", true, 1, listOf(
            WidgetSnapshot(0, "agui::ImageWidget", "", true, 0, 0, 10, 10, false,
                properties = WidgetProperties(image = WidgetImage(true, "GuiAnimation"))),
        ), imageUnavailableReason = "Missing matching metadata")
        val structure = snapshot.uiJson(false, fields = emptySet())
        assertNull(structure["image_unavailable_reason"])
        assertNull(structure.getValue("nodes").jsonArray.single().jsonObject["properties"])
        val metadata = snapshot.uiJson(false, fields = setOf(UiField.IMAGE))
        assertEquals(JsonPrimitive("Missing matching metadata"), metadata["image_unavailable_reason"])
        val properties = metadata.getValue("nodes").jsonArray.single().jsonObject.getValue("properties").jsonObject
        assertEquals(setOf("image"), properties.keys)
        assertEquals(setOf("native_type"), properties.getValue("image").jsonObject.keys)
    }
}
