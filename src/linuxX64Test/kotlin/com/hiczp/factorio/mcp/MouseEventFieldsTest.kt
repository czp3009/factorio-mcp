package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertFails

class MouseEventFieldsTest {
    @Test
    fun rejectsOverlappingAndOutOfBoundsResolvedFields() {
        val fields = MouseEventFields(64, 16, 20, 56, 24, 28, 32, 40, 8, 9, 10)
        for (copy in
            listOf<() -> MouseEventFields>(
                { fields.copy(alt = fields.control) },
                { fields.copy(shift = fields.time) },
                { fields.copy(type = fields.button) },
                { fields.copy(extent = 60) },
                { fields.copy(wheel = -1) },
            )) assertFails { copy() }
    }
}
