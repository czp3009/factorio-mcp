@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.acceptance

import com.hiczp.factorio.mcp.ControlLayouts
import com.hiczp.factorio.mcp.ElfImage
import com.hiczp.factorio.mcp.MappedBinary
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertTrue

/** Explicit installed-file metadata checks, with no process launch, injection or control dispatch. */
class ControlMetadataAcceptanceTest {
    @Test
    fun resolvesControlRegistryAndKeyboardMouseBindings() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val metadata = ControlLayouts.resolve(image)
            var reads = 0
            metadata.verify(image, 0) { address, length ->
                ++reads
                image.virtualBytes(address, length.toLong()).bytes(0, length)
            }
            assertTrue(reads > 0)
            assertFails {
                metadata.verify(image, 0) { address, length ->
                    image.virtualBytes(address, length.toLong()).bytes(0, length).also {
                        it[0] = (it[0].toInt() xor 1).toByte()
                    }
                }
            }
            println("Verified control registry ${metadata.registry.name}, extent ${metadata.size}, ${metadata.names}")
            println("Verified keyboard/mouse slots ${metadata.slots}, modifiers ${metadata.modifiers}")
            println("Verified custom prototype ${metadata.prototype}, GUI ${metadata.gui}, raw usage ${metadata.usage}")
            println("Verified empty kind ${metadata.emptyKind}, mouse kind ${metadata.mouseKind}: ${metadata.mouseNames}")
            println("Verified wheel codes ${metadata.wheel}; $reads loaded-evidence ranges")
        }
    }
}
