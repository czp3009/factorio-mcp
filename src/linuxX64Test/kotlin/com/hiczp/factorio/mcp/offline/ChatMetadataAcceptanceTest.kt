@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.offline

import com.hiczp.factorio.mcp.ChatMetadata
import com.hiczp.factorio.mcp.ElfImage
import com.hiczp.factorio.mcp.MappedBinary
import com.hiczp.factorio.mcp.linuxbridge.FmLinuxChatConfig
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

/** Explicit file-backed integration, without runtime injection or submission. */
class ChatMetadataAcceptanceTest {
    @Test
    fun assemblesAndChecksAllNativeChatConfiguration(): Unit = memScoped {
        MappedBinary(checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")).toKString()).use { file ->
            val image = ElfImage(file.view)
            val metadata = ChatMetadata.resolve(image)
            for (evidence in listOf(metadata.admission.evidence, metadata.evidence)) {
                evidence.verify(image, 0) { address, size -> image.virtualBytes(address, size.toLong()).bytes(0, size) }
            }
            val config = alloc<FmLinuxChatConfig>()
            metadata.writeTo(config, 0)
            assertEquals(metadata.action.type.toUInt(), config.actionType)
            assertEquals(metadata.action.size.toUInt(), config.actionSize)
            assertEquals(metadata.string.layout.size.toUInt(), config.stringSize)
            val original = config.constructAction
            metadata.writeTo(config, 0x100000)
            assertEquals(original + 0x100000u, config.constructAction)
            assertFails { metadata.writeTo(config, -8) }
            println(
                "Verified complete native chat configuration: ${metadata.action}, " +
                        "${metadata.evidence.functions.size} action functions and ${metadata.evidence.pointers.size} pointer words"
            )
        }
    }
}
