package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class NativeListLinksTest {
    private val node = NativeListNodeLayout(0, 8, 24, 96)
    private val code = "48 8b 47 08 48 8b 57 10 48 89 42 08 48 89 50 10 c3"

    @Test
    fun derivesTheReciprocalLinkWithoutAssumingItsPosition() {
        assertEquals(16L, NativeListLinks.analyze(machineCode(code), node))
    }

    @Test
    fun rejectsChangedReceiversStoresWidthsBoundsAndFrame() {
        for (invalid in listOf(
            code.replace("47 08", "46 08"), code.replace("57 10", "57 08"),
            code.replace("42 08", "42 10"), code.replace("50 10", "50 08"),
            code.replace("48 89 50", "40 89 50"), code.replace("48 8b 47", "48 8b 5f"),
            "53 " + code, code.replace("57 10", "57 18"),
        )) assertFails { NativeListLinks.analyze(machineCode(invalid), node) }
    }
}
