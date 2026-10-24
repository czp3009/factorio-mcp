@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.offline

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
    fun resolvesPresentationCallInSelectedExecutable() {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")).toKString()
        MappedBinary(path).use {
            val image = ElfImage(it.view)
            val metadata = FrameContextMetadata.resolve(image)
            val result = metadata.swap
            assertTrue(result.caller > 0 && result.api.slot > 0)
            println("Verified selected graphics presentation call: $result")
            val size = metadata.size
            println("Verified selected graphics framebuffer context: $size")
            assertTrue(metadata.evidence.functions.isNotEmpty() && metadata.evidence.pointers.size >= 8)
        }
    }
}
