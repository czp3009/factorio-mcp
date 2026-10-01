@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.acceptance

import com.hiczp.factorio.mcp.ChatAdmissionMetadata
import com.hiczp.factorio.mcp.ElfImage
import com.hiczp.factorio.mcp.MappedBinary
import com.hiczp.factorio.mcp.linuxbridge.FmLinuxChatAdmissionConfig
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test

/** Explicit installed-file configuration check; never attaches to or mutates a game. */
class ChatAdmissionAcceptanceTest {
    @Test
    fun assemblesBoundedNativeAdmissionConfiguration(): Unit = memScoped {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")).toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val metadata = ChatAdmissionMetadata.resolve(image)
            val layout = metadata.layout
            layout.writeTo(alloc<FmLinuxChatAdmissionConfig>(), 0)
            // A file-backed comparison checks captured ranges/classification, not a live attachment.
            metadata.evidence.verify(image, 0) { address, size ->
                image.virtualBytes(address, size.toLong()).bytes(0, size)
            }
            println("Verified native chat admission layout: $layout")
            println(
                "Verified admission evidence: ${metadata.evidence.functions.size} functions, " +
                        "${metadata.evidence.readonly.size} readonly ranges, ${metadata.evidence.pointers.size} pointer words"
            )
        }
    }
}
