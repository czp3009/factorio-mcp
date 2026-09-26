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
            "Label $id",
            "Description",
            if (linked) "confirm-gui" else null,
            if (linked) "confirm-gui" else id,
            linked,
            if (linked) false else null,
            if (linked) true else null,
            if (linked) false else null,
            true,
            "Normal",
            listOf(unbound),
            if (linked) listOf(key) else listOf(unbound),
            false,
        )

    @Test
    fun linkedDisabledControlStillReportsEffectiveBinding() {
        val row = control("custom", true).json()
        assertEquals(false, row.getValue("enabled").jsonPrimitive.boolean)
        assertTrue(row.getValue("has_binding").jsonPrimitive.boolean)
        assertTrue(row.getValue("bindings").jsonArray.isEmpty())
        val effective = row.getValue("effective_bindings").jsonArray[0].jsonObject
        assertEquals("E", effective.getValue("name").jsonPrimitive.content)
        assertEquals("control", effective.getValue("modifiers").jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun filteringPaginationAndIncompleteAbsenceAreDistinct() {
        val snapshot =
            GameSnapshot(
                "main_menu",
                true,
                1,
                controls = listOf(control("z"), control("a")),
                registryCount = 2,
            )
        val page = snapshot.bindingsJson(null, "LABEL", 0, 1)
        assertEquals(2, page.getValue("matched_count").jsonPrimitive.int)
        assertEquals(1, page.getValue("next_offset").jsonPrimitive.int)
        assertEquals(
            "a",
            page.getValue("controls").jsonArray[0].jsonObject.getValue("id").jsonPrimitive.content,
        )
        val ids = setOf("a", "missing")
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
