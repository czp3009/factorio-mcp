@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.offline

import com.hiczp.factorio.mcp.ElfImage
import com.hiczp.factorio.mcp.MappedBinary
import com.hiczp.factorio.mcp.SysVObjectSize
import com.hiczp.factorio.mcp.WidgetRenderFlag
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

/** Explicit installed-file analysis; never starts a client or invokes game functions. */
class WidgetRenderAcceptanceTest {
    @Test
    fun resolvesNamedRenderPredicates() {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val size = SysVObjectSize.resolve(image, "4agui6Widget")
            val metadata = WidgetRenderFlag.resolve(image, size)
            assertEquals(1UL, metadata.field.maximum)
            val bias = 0x100000000L
            var reads = 0
            metadata.verify(image, bias) { address, length ->
                ++reads
                image.virtualBytes(address - bias, length.toLong()).bytes(0, length)
            }
            check(reads > 0)
            assertFails {
                metadata.verify(image, bias) { address, length ->
                    image.virtualBytes(address - bias, length.toLong()).bytes(0, length).also {
                        it[0] = (it[0].toInt() xor 1).toByte()
                    }
                }
            }
            println("Verified own widget render flag: ${metadata.field}; $reads function/data ranges")
        }
    }
}
