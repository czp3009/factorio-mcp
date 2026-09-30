@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxScriptLayout
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class LuaContextLayoutTest {
    private val context = LuaContextLayout(80, FirstNamedPointer(24, 32, 16, listOf(97, 0, 98).map(Int::toByte)), 0, 8)
    private val script = LuaScriptLayout(80, 48, 0x2010, 0x3000, ScriptReadiness(64, 65))

    @Test
    fun writesDerivedSelectionAndRawNameBytes(): Unit = memScoped {
        val output = alloc<FmLinuxScriptLayout>()
        context.writeTo(output, script, 0x100000)
        assertEquals(0x102010uL, output.vtable)
        assertEquals(0x103000uL, output.typeInfo)
        assertEquals(80u, output.contextSize)
        assertEquals(80u, output.scriptSize)
        assertEquals(24u, output.begin)
        assertEquals(32u, output.end)
        assertEquals(16u, output.name)
        assertEquals(0u, output.stringData)
        assertEquals(8u, output.stringLength)
        assertEquals(48u, output.state)
        assertEquals(64u, output.loading)
        assertEquals(65u, output.enabled)
        assertEquals(3u, output.expectedSize)
        assertEquals(listOf(97u, 0u, 98u).map(UInt::toUByte), (0..2).map { output.expected[it] })
    }

    @Test
    fun rejectsOverlapsBoundsAndInvalidRelocations(): Unit = memScoped {
        val output = alloc<FmLinuxScriptLayout>()
        for (bias in listOf(-1L, 1L, Long.MAX_VALUE)) assertFails { context.writeTo(output, script, bias) }
        for (element in listOf(
            script.copy(size = 8), script.copy(state = 16), script.copy(state = 0),
            script.copy(vtable = 1), script.copy(typeInfo = 0),
            script.copy(readiness = ScriptReadiness(64, 64)), script.copy(readiness = ScriptReadiness(0, 65)),
            script.copy(readiness = ScriptReadiness(64, 80)), script.copy(readiness = ScriptReadiness(48, 65))
        )) {
            assertFails { context.writeTo(output, element, 0) }
        }
        for (selection in listOf(
            context.script.copy(begin = 0), context.script.copy(end = 24),
            context.script.copy(end = 28), context.script.copy(name = 80), context.script.copy(expected = emptyList()),
            context.script.copy(expected = List(65) { 0 })
        )) {
            assertFails { context.copy(script = selection).writeTo(output, script, 0) }
        }
    }
}
