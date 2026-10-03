@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.offline

import com.hiczp.factorio.mcp.ElfImage
import com.hiczp.factorio.mcp.MappedBinary
import com.hiczp.factorio.mcp.SysVObjectSize
import com.hiczp.factorio.mcp.ViewportLayout
import com.hiczp.factorio.mcp.linuxbridge.FmLinuxViewportLayout
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/** Selected-file proofs only. This test does not call game functions or establish live viewport coverage. */
class ViewportMetadataAcceptanceTest {
    @Test
    fun resolvesDisplayFramebuffer() {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")).toKString()
        MappedBinary(path).use {
            val image = ElfImage(it.view)
            val metadata = ViewportLayout.resolve(image, SysVObjectSize.resolve(image, "8GameView"))
            println("Viewport display: ${metadata.display}")
            println("Viewport coordinates: ${metadata.coordinates}")
            println("Viewport framebuffer: size=${metadata.framebufferSize}, backing=${metadata.backing}")
            var reads = 0
            metadata.evidence.verify(image, 0) { address, size ->
                ++reads
                image.virtualBytes(address, size.toLong()).bytes(0, size)
            }
            assertTrue(reads > 0 && metadata.evidence.readonly.isNotEmpty())
            assertFails {
                metadata.evidence.verify(image, 0) { address, size ->
                    image.virtualBytes(address, size.toLong()).bytes(0, size).also {
                        it[0] = (it[0].toInt() xor 1).toByte()
                    }
                }
            }
            memScoped {
                val output = alloc<FmLinuxViewportLayout>()
                metadata.writeTo(output, 0)
                assertEquals(metadata.coordinates.position.toUInt(), output.position)
                assertEquals(metadata.coordinates.surface.toUInt(), output.surface)
                assertEquals(metadata.display.framebufferReference.toUInt(), output.framebufferReference)
                assertEquals(metadata.coordinates.mapPosition.address.toULong(), output.mapPosition)
            }
            println("Verified $reads file-derived code/data ranges and rejected changed evidence")
        }
    }
}
