package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InputBindingsTest {
    private val unbound =
        BindingSnapshot("keyboard_mouse_primary", "Nothing", null, 0, emptyList(), 0)
    private val key =
        BindingSnapshot("keyboard_mouse_primary", "Keyboard", "E", 8, listOf("control"), 1)

    private fun control(id: String, linked: Boolean = false) =
        ControlSnapshot(
            id,
            if (linked) "confirm-gui" else null,
            if (linked) "confirm-gui" else id,
            linked,
            if (linked) false else null,
            if (linked) true else null,
            if (linked) false else null,
            true,
            -77,
            listOf(unbound),
            if (linked) listOf(key) else listOf(unbound),
        )

    @Test
    fun linkedDisabledControlStillReportsEffectiveBinding() {
        val row = control("custom", true).json()
        assertEquals(
            setOf(
                "id",
                "custom",
                "gui_input",
                "native_usage",
                "enabled",
                "enabled_while_spectating",
                "enabled_while_in_cutscene",
                "linked_control",
                "binding_owner",
                "has_binding",
                "bindings",
                "effective_bindings"
            ),
            row.keys,
        )
        assertEquals(-77, row.getValue("native_usage").jsonPrimitive.int)
        assertEquals(false, row.getValue("enabled").jsonPrimitive.boolean)
        assertTrue(row.getValue("has_binding").jsonPrimitive.boolean)
        assertTrue(row.getValue("bindings").jsonArray.isEmpty())
        val effective = row.getValue("effective_bindings").jsonArray[0].jsonObject
        assertEquals("E", effective.getValue("name").jsonPrimitive.content)
        assertEquals("control", effective.getValue("modifiers").jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun controllerAndUnknownTypesDoNotCountAsKeyboardMouseBindings() {
        for (type in listOf("ControllerButton", "ControllerAxis", "Unknown(4)", "Nothing")) {
            val row = control("fixture").copy(
                bindings = listOf(key.copy(type = type)),
                effective = listOf(key.copy(type = type)),
            ).json()
            assertFalse(row.getValue("has_binding").jsonPrimitive.boolean)
        }
        for (type in listOf("Keyboard", "MouseButton", "MouseWheel")) {
            val row = control("fixture").copy(effective = listOf(key.copy(type = type))).json()
            assertTrue(row.getValue("has_binding").jsonPrimitive.boolean)
        }
    }

    @Test
    fun filteringPaginationAndIncompleteAbsenceAreDistinct() {
        val snapshot =
            GameSnapshot(
                "main_menu",
                true,
                1,
                controls = listOf(control("control-z"), control("control-a")),
                registryCount = 2,
            )
        val page = snapshot.bindingsJson(null, "CONTROL", 0, 1)
        assertEquals(2, page.getValue("matched_count").jsonPrimitive.int)
        assertEquals(1, page.getValue("next_offset").jsonPrimitive.int)
        assertEquals(
            "control-a",
            page.getValue("controls").jsonArray[0].jsonObject.getValue("id").jsonPrimitive.content,
        )
        val ids = setOf("control-a", "missing")
        assertEquals(
            "missing",
            snapshot
                .bindingsJson(ids, null, 0, 10)
                .getValue("missing_ids")
                .jsonArray
                .single()
                .jsonPrimitive
                .content,
        )
        val incomplete =
            snapshot.copy(truncated = true, registryCount = 10000).bindingsJson(ids, null, 0, 10)
        assertFalse(incomplete.getValue("snapshot_complete").jsonPrimitive.boolean)
        assertFalse("missing_ids" in incomplete)
        assertEquals(
            "missing",
            incomplete.getValue("unobserved_ids").jsonArray.single().jsonPrimitive.content,
        )
    }
}
