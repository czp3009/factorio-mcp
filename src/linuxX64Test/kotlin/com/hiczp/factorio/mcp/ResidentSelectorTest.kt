@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxUiSelector
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlin.test.Test
import kotlin.test.assertEquals

class ResidentSelectorTest {
    @Test
    fun absentOptionalSelectorClearsPreviousPathForCurrentFocusActions() = memScoped {
        val selector = alloc<FmLinuxUiSelector>()
        selector.count = 2u
        selector.writePath(emptyList())
        assertEquals(0u, selector.count)
        selector.count = 2u
        selector.writePath(null)
        assertEquals(0u, selector.count)
    }
}
