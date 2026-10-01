@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.acceptance

import com.hiczp.factorio.mcp.ElfImage
import com.hiczp.factorio.mcp.FrameContextMetadata
import com.hiczp.factorio.mcp.MappedBinary
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertTrue

/** Explicit static check; does not install hooks or call the graphics driver. */
class GraphicsSwapAcceptanceTest {
    @Test
    fun resolvesBackendCallInSelectedExecutable() {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")).toKString()
        MappedBinary(path).use {
            val image = ElfImage(it.view)
            val metadata = FrameContextMetadata.resolve(image)
            val result = metadata.swap
            assertTrue(result.caller > 0 && result.member >= 0)
            println("Verified selected graphics backend call: $result")
            val size = metadata.size
            println("Verified selected graphics framebuffer getter: $size")
            assertTrue(metadata.evidence.functions.isNotEmpty() && metadata.evidence.pointers.size >= 8)
        }
    }
}
