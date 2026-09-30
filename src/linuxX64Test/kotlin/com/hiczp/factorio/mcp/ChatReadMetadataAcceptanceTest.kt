@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test

/** Explicit installed-file checks; no process attachment or console mutation. */
class ChatReadMetadataAcceptanceTest {
    @Test
    fun resolvesTypedConsoleNodes() {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")).toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val layout = NativeListNodeLayout.resolve(
                image,
                "_ZNSt7__cxx1110_List_baseIN13OutputConsole4ItemESaIS2_EED2Ev",
                "_ZN13OutputConsole4ItemD2Ev"
            )
            println("Verified native console node: $layout")
            println("Verified embedded console LocalisedString: ${ChatRecordText.resolve(image, layout)}")
            val string = NativeStringLayout.resolve(image)
            val text = LocalisedTextLayout.resolve(image, string)
            println("Verified raw text call placement: ${LocalisedRawCall.resolve(image, string, text)}")
            require(ChatRecordText.resolve(image, layout) + text.size <= layout.size - layout.value)
            println("Verified bounded console text/cache layout: $text")
            val playerSize = SysVLineAllocation.resolve(
                image,
                "_ZN3Map8loadDataER15MapDeserialiserRK17GlobalModSettingsP16ProgressObserver",
                "_ZN6PlayerC2ER3MapR15MapDeserialiser"
            )
            val console = ChatConsoleMember.resolve(image, playerSize)
            println("Verified Player console member: $console")
            val lists = ChatConsoleLists.resolve(image, console, layout)
            println("Verified console list reset groups: $lists")
            val consoleSize = ChatConsoleExtent.resolve(image, console, playerSize, lists)
            println("Verified complete console extent: $consoleSize")
            println("Verified console storage roles: ${ChatConsoleStreams.resolve(image, lists, layout)}")
            ChatConsoleCounts.verify(image, lists)
            println("Verified native list count decrements")
            val index = PlayerIndex.resolve(image, playerSize, LuaStateLayout.resolve(image))
            val player =
                ChatRecordPlayer.resolve(image, layout, ChatRecordText.resolve(image, layout), text.size, index)
            println("Verified console record Player index: $player")
            println(
                "Verified console record tick: ${
                    ChatRecordTick.resolve(
                        image, layout, consoleSize, playerSize,
                        ChatRecordText.resolve(image, layout), text.size, player, index
                    )
                }"
            )
        }
    }
}
