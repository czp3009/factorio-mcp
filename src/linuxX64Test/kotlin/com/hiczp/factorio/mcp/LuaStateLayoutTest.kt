@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxLuaStateLayout
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class LuaStateLayoutTest {
    private val layout = LuaStateLayout(
        LuaAllocation(256, 8, 128, 0, 8, 16),
        LuaStackLayout(16, 24, 0, 16), 32, 40, 80, LuaProtectedCall(5, 1, 8), 48
    )

    @Test
    fun transfersOnlyBoundedNonoverlappingDerivedFields(): Unit = memScoped {
        val wire = alloc<FmLinuxLuaStateLayout>()
        layout.writeTo(wire)
        assertEquals(256u, wire.allocationSize)
        assertEquals(128u, wire.globalOffset)
        assertEquals(8u, wire.global)
        assertEquals(16u, wire.mainState)
        assertEquals(16u, wire.top)
        assertEquals(24u, wire.callInfo)
        assertEquals(32u, wire.stackBase)
        assertEquals(40u, wire.stackEnd)
        assertEquals(80u, wire.baseFrame)
        assertEquals(0u, wire.function)
        assertEquals(8u, wire.frameTop)
        assertEquals(1u, wire.status)
        assertEquals(48u, wire.handler)
        assertEquals(16u, wire.valueSize)
        assertFails { layout.copy(handler = 16) }
        assertFails { layout.copy(call = layout.call.copy(status = 8)) }
        assertFails { layout.copy(baseFrame = 120) }
        assertFails { layout.copy(allocation = layout.allocation.copy(mainState = 129)) }
        assertFails { layout.copy(stackEnd = Long.MAX_VALUE) }
        assertFails { layout.copy(call = layout.call.copy(frameTop = Long.MAX_VALUE)) }
        assertFails { layout.copy(stackBase = 33) }
        assertFails { layout.copy(allocation = layout.allocation.copy(globalOffset = -1)) }
    }
}
