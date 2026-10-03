@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.offline

import com.hiczp.factorio.mcp.ElfImage
import com.hiczp.factorio.mcp.GraphicsSwapCall
import com.hiczp.factorio.mcp.MappedBinary
import com.hiczp.factorio.mcp.SdlDeviceAllocation
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals

/** Explicit selected-file allocation proof only; never accesses or patches a live device. */
class SdlDeviceAcceptanceTest {
    @Test
    fun resolvesDeviceBoundsAndBackendCallbackStores() {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")).toKString()
        MappedBinary(path).use {
            val image = ElfImage(it.view)
            val swap = GraphicsSwapCall.resolve(image)
            for ((factory, backend) in listOf(
                "X11_CreateDevice" to "X11_GL_SwapWindow",
                "X11_CreateDevice" to "X11_GLES_SwapWindow",
                "Wayland_CreateDevice" to "Wayland_GLES_SwapWindow",
            )) {
                val result = SdlDeviceAllocation.resolve(image, factory, backend)
                assertEquals(swap.member, result.member)
                println("Verified $backend allocation: $result")
            }
        }
    }
}
