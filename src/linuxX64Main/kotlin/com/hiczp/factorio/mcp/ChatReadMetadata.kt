@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxChatReadConfig
import kotlinx.cinterop.set

/** Complete native fallback for retained console history, with evidence from one selected executable. */
internal data class ChatReadMetadata(
    val world: WorldLayout,
    val player: PlayerLayout,
    val console: Long,
    val consoleSize: Long,
    val lists: ChatConsoleLists,
    val streams: ChatConsoleStreams,
    val node: NativeListNodeLayout,
    val previous: Long,
    val text: Long,
    val localised: LocalisedTextLayout,
    val recordPlayer: ChatRecordPlayer,
    val tick: ChatRecordTick,
    val raw: LocalisedRawCall,
    val string: NativeStringLayout,
    val evidence: ElfEvidence,
) {
    fun verifyLoaded(image: ElfImage, process: ProcessHandle, bias: Long) =
        evidence.verify(image, bias, process::readMemory)

    fun writeTo(output: FmLinuxChatReadConfig, bias: Long) {
        require(
            bias >= 0 && bias % 8 == 0L && raw.output == 7 && raw.receiver == 6 &&
                    raw.function > 0 && raw.function <= Long.MAX_VALUE - bias &&
                    string.destructor.address > 0 && string.destructor.address <= Long.MAX_VALUE - bias
        )
        world.writeTo(output.world, bias)
        player.writeTo(output.player, bias)
        output.console = console.toUInt()
        output.raw = (raw.function + bias).toULong()
        output.destroyString = (string.destructor.address + bias).toULong()
        val layout = output.layout
        layout.consoleSize = consoleSize.toUInt()
        layout.consolePlayer = tick.consolePlayer.toUInt()
        listOf(streams.gameState, streams.local).forEachIndexed { index, sentinel ->
            val list = lists.lists.single { it.sentinel == sentinel }
            layout.sentinel[index] = sentinel.toUInt()
            layout.count[index] = list.count.toUInt()
        }
        layout.nodeSize = node.size.toUInt()
        layout.next = node.next.toUInt()
        layout.previous = previous.toUInt()
        layout.value = node.value.toUInt()
        layout.tick = tick.offset.toUInt()
        layout.playerIndex = recordPlayer.offset.toUInt()
        layout.indexWidth = player.index.width.toUInt()
        layout.text = text.toUInt()
        layout.textSize = localised.size.toUInt()
        layout.cached = localised.cached.toUInt()
        layout.stringSize = string.size.toUInt()
        layout.stringData = string.data.toUInt()
        layout.stringLength = string.length.toUInt()
    }

    companion object {
        fun resolve(image: ElfImage): ChatReadMetadata {
            val pointers = mutableMapOf<Long, Long>()
            val scalars = mutableMapOf<Long, Long>()
            val (resolved, readonly) = image.withReadonlyEvidence {
                image.withFunctionEvidence {
                    val world = WorldLayout.resolve(image)
                    val player = PlayerLayout.resolve(image, world.gameSize, LuaStateLayout.resolve(image))
                    val node = NativeListNodeLayout.resolve(
                        image,
                        "_ZNSt7__cxx1110_List_baseIN13OutputConsole4ItemESaIS2_EED2Ev", "_ZN13OutputConsole4ItemD2Ev"
                    )
                    val string = NativeStringLayout.resolve(image)
                    val localised = LocalisedTextLayout.resolve(image, string)
                    val text = ChatRecordText.resolve(image, node)
                    val console = ChatConsoleMember.resolve(image, player.playerSize)
                    val lists = ChatConsoleLists.resolve(image, console, node)
                    val size = ChatConsoleExtent.resolve(image, console, player.playerSize, lists)
                    ChatConsoleCounts.verify(image, lists)
                    val streams = ChatConsoleStreams.resolve(image, lists, node)
                    val recordPlayer = ChatRecordPlayer.resolve(image, node, text, localised.size, player.index)
                    val tick = ChatRecordTick.resolve(
                        image,
                        node,
                        size,
                        player.playerSize,
                        text,
                        localised.size,
                        recordPlayer,
                        player.index
                    )
                    val raw = LocalisedRawCall.resolve(image, string, localised)
                    val reader = image.pointers
                    for (type in listOf("10LuaContext", "6Player", "8GameView")) {
                        val identity = ItaniumType.resolve(image, type)
                        scalars[identity.addressPoint - 16] = 0
                        pointers[identity.addressPoint - 8] = identity.typeInfo
                        pointers[identity.typeInfo + 8] = reader.words(identity.typeInfo + 8, 1).single().pointer()
                    }
                    val destructor =
                        ItaniumVtable.resolve(image, "_ZTV10LuaContext").method(image, "_ZN10LuaContextD0Ev")
                    pointers[destructor.entryAddress] = destructor.function.address
                    ChatReadMetadata(
                        world,
                        player,
                        console,
                        size,
                        lists,
                        streams,
                        node,
                        NativeListLinks.resolve(image, node),
                        text,
                        localised,
                        recordPlayer,
                        tick,
                        raw,
                        string,
                        ElfEvidence(emptyList(), emptyList(), emptyMap(), emptyMap())
                    )
                }
            }
            return resolved.first.copy(evidence = ElfEvidence(resolved.second, readonly, pointers, scalars))
        }
    }
}
