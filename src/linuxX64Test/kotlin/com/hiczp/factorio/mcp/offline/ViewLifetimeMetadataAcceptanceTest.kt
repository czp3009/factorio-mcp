@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.offline

import com.hiczp.factorio.mcp.*

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertTrue

/** Explicit installed-file evidence checks. Does not install a hook or retire any object. */
class ViewLifetimeMetadataAcceptanceTest {
    @Test
    fun resolvesGameViewRetirementBoundary() {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val size = SysVOwnedObjectSize.resolve(
                image,
                "_ZNSt10unique_ptrI4GameSt14default_deleteIS0_EED2Ev", "_ZN4GameD2Ev"
            ).size
            val member = SysVMemberCalls.directPrefix(image, "_ZN4GameD2Ev", "_ZN8GameView9unloadGuiEv", size)
            val metadata =
                ViewRetirementMetadata.resolve(image, size, SysVObjectSize.resolve(image, "8GameView"), member)
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
            println("Verified GameView retirement: ${metadata.layout}; $reads code/data ranges")
        }
    }
}
