@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxUiSelector
import com.hiczp.factorio.mcp.linuxbridge.FmRequestBuffer
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlin.test.Test
import kotlin.test.assertEquals

class ResidentSelectorTest {
    @Test
    fun absentOptionalSelectorClearsPreviousPathForCurrentFocusActions() = memScoped {
        val selector = alloc<FmLinuxUiSelector>()
        val root = alloc<FmRequestBuffer>()
        root.address = 0uL
        root.size = 0uL
        selector.count = 2u
        selector.writePath(UiAction(emptyList()), RequestPayload(), root)
        assertEquals(0u, selector.count)
        assertEquals(defaultUiFields.uiFieldMask(), selector.fields)
        selector.writePath(UiAction(emptyList(), fields = emptySet()), RequestPayload(), root)
        assertEquals(0u, selector.fields)
        selector.count = 2u
        selector.writePath(null, RequestPayload(), root)
        assertEquals(0u, selector.count)
        assertEquals(0uL, selector.path.address)
        assertEquals(0uL, selector.path.size)
        assertEquals(0u, selector.collection)
    }
}
