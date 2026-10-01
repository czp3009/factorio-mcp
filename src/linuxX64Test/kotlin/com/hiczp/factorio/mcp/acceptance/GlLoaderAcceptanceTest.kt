@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.acceptance

import com.hiczp.factorio.mcp.ElfImage
import com.hiczp.factorio.mcp.GlLoaderBinding
import com.hiczp.factorio.mcp.MappedBinary
import com.hiczp.factorio.mcp.SdlDeviceAllocation
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test

/** Explicit selected-file loader-name proof. Does not call a graphics API or patch the process. */
class GlLoaderAcceptanceTest {
    @Test
    fun resolvesSdkNamesForAllCaptureEntries() {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")).toKString()
        MappedBinary(path).use {
            val image = ElfImage(it.view)
            val device = SdlDeviceAllocation.resolve(image, "X11_CreateDevice", "X11_GL_SwapWindow")
            val result = GlLoaderBinding.resolve(
                image, listOf(
                    "glGetIntegerv", "glBindFramebuffer", "glBindBuffer",
                    "glPixelStorei", "glReadPixels", "glGetError"
                ), device.size
            )
            println("Verified OpenGL SDK lookup bindings: $result")
        }
    }
}
