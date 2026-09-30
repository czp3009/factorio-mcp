@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxChatReadConfig
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Explicit selected-file composition and wire check; never attaches to or invokes the game. */
class ChatReadCompositionAcceptanceTest {
    @Test
    fun resolvesCompleteChatReaderAndWire() {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")).toKString()
        MappedBinary(path).use { file ->
            val metadata = ChatReadMetadata.resolve(ElfImage(file.view))
            memScoped {
                val output = alloc<FmLinuxChatReadConfig>()
                metadata.writeTo(output, 0)
                assertEquals(metadata.console.toUInt(), output.console)
                assertEquals(metadata.streams.gameState.toUInt(), output.layout.sentinel[0])
                assertEquals(metadata.streams.local.toUInt(), output.layout.sentinel[1])
                assertEquals(metadata.tick.offset.toUInt(), output.layout.tick)
                assertEquals(metadata.string.size.toUInt(), output.layout.stringSize)
                assertEquals(metadata.raw.function.toULong(), output.raw)
                assertEquals(metadata.string.destructor.address.toULong(), output.destroyString)
            }
            assertTrue(metadata.evidence.functions.isNotEmpty() && metadata.evidence.pointers.isNotEmpty())
            println(
                "Verified complete chat reader: console=${metadata.consoleSize}, node=${metadata.node.size}, " +
                        "streams=${metadata.streams}, code=${metadata.evidence.functions.size}, pointers=${metadata.evidence.pointers.size}"
            )
        }
    }
}
