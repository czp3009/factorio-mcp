package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ItaniumAncestryTest {
    private val base = ItaniumClass(100, emptyList())
    private fun edge(type: Long, offset: Long = 0, public: Boolean = true, virtual: Boolean = false) =
        ItaniumClass.Base(type, offset, public, virtual)

    @Test
    fun accumulatesValidatedBaseDisplacements() {
        val parent = ItaniumClass(200, listOf(edge(100, 8)))
        val derived = ItaniumClass(300, listOf(edge(200, 16)))
        val types = listOf(base, parent, derived).associateBy { it.typeInfo }
        assertEquals(24, derived.baseOffset(base, 64, 40, types::getValue))
        assertEquals(0, base.baseOffset(base, 40, 40, types::getValue))
        assertFails { derived.baseOffset(base, 63, 40, types::getValue) }
        assertFails { derived.baseOffset(base, 64, 40) { base } }
    }

    @Test
    fun rejectsAmbiguityCyclesAndUnsupportedBases() {
        val left = ItaniumClass(200, listOf(edge(100)))
        val right = ItaniumClass(300, listOf(edge(100)))
        val types = listOf(base, left, right).associateBy { it.typeInfo }
        val diamond = ItaniumClass(400, listOf(edge(200), edge(300, 16)))
        assertFails { diamond.baseOffset(base, 64, 4, types::getValue) }
        for (entry in listOf(edge(100, -1), edge(100, 64), edge(100, public = false), edge(100, virtual = true))) {
            assertFails { ItaniumClass(400, listOf(entry)).baseOffset(base, 64, 4, types::getValue) }
        }
        val cycle = ItaniumClass(200, listOf(edge(300)))
        val back = ItaniumClass(300, listOf(edge(200)))
        val cyclic = listOf(cycle, back).associateBy { it.typeInfo }
        assertFails { cycle.baseOffset(base, 64, 4, cyclic::getValue) }
        assertFails { ItaniumClass(400, emptyList()).baseOffset(base, 64, 4, types::getValue) }
    }
}
