package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.ConstructorValues.Argument
import com.hiczp.factorio.mcp.ConstructorValues.Load
import com.hiczp.factorio.mcp.X64Instructions.*

/** Map tick copied into a fresh record. Runtime must match the console back-pointer to the selected Player. */
internal data class ChatRecordTick(val offset: Long, val consolePlayer: Long, val playerMap: Long, val mapTick: Long) {
    companion object {
        fun resolve(
            image: ElfImage, node: NativeListNodeLayout, consoleSize: Long, playerSize: Long,
            text: Long, textSize: Long, player: ChatRecordPlayer, index: PlayerIndex
        ): ChatRecordTick {
            val playerMap = SysVArgumentMember.resolve(image, "_ZN6PlayerC2ER3MapR15MapDeserialiser", playerSize)
            val mapSize = SysVOwnedObjectSize.resolve(
                image,
                "_ZNSt10unique_ptrI3MapSt14default_deleteIS0_EED2Ev", "_ZN3MapD2Ev"
            ).size
            val function =
                image.symbol("_ZN13OutputConsole3addERK15LocalisedStringPK6PlayerRK13PrintSettingsOSt6vectorI25SavedSpecialItemReferenceSaISA_EE")
            val allocate = image.symbol("_Znwm")
            EhFrames(image).function(allocate)
            val construction =
                "construct_at<OutputConsole::Item, MapTick &, const LocalisedString &, const Player *&, const Color &, std::vector<SavedSpecialItemReference, std::allocator<SavedSpecialItemReference> > >"
            val instances = image.inlines.find(function, "add", setOf("Item", construction))
            val copies = instances.filter { it.name == "Item" }.flatMap { it.ranges }
            val arguments = instances.filter { it.name == construction }.flatMap { it.ranges }
            require(copies.all { copy -> arguments.any { it.contains(copy) } })
            fun relative(ranges: List<DwarfRanges.Range>) = ranges.map {
                DwarfRanges.Range(it.start - function.address, it.end - function.address)
            }
            return analyze(
                X64ControlFlow.resolve(image, function), allocate.address - function.address, node,
                consoleSize, playerMap, mapSize, text, textSize, player.offset, index.width,
                relative(copies), relative(arguments)
            )
        }

        fun analyze(
            flow: X64ControlFlow, allocator: Long, node: NativeListNodeLayout, consoleSize: Long,
            playerMap: Long, mapSize: Long, text: Long, textSize: Long, player: Long, indexWidth: Int,
            copies: List<DwarfRanges.Range>, arguments: List<DwarfRanges.Range>
        ): ChatRecordTick {
            require(
                consoleSize in 8..4096 && playerMap >= 0 && mapSize in 8..(64 * 1024 * 1024) &&
                        playerMap % 8 == 0L && indexWidth in 1..2
            )
            require(
                node.value > 0 && node.size in node.value + 8..4096 && text >= 0 && textSize > 0 &&
                        text + textSize <= node.size - node.value && player in 0..node.size - node.value - indexWidth
            )
            require(copies.isNotEmpty() && arguments.isNotEmpty() && copies.all { copy ->
                copy.start >= 0 && copy.start < copy.end && arguments.any { it.contains(copy) }
            })
            val allocation = NativeAllocationResult.find(flow, allocator, node.size)
            val nextCall =
                flow.instructions.firstOrNull { it.offset > allocation.offset && it.operation == Operation.CALL }
                    ?: error("Record construction has no following native construction call")
            val stores = flow.instructions.filter { instruction ->
                val memory = instruction.destination as? Memory
                val source = instruction.source as? Register
                instruction.offset in allocation.offset + allocation.size until nextCall.offset &&
                        instruction.offset in flow.reachable && copies.any {
                    instruction.offset >= it.start && instruction.offset + instruction.size <= it.end
                } && instruction.operation == Operation.MOV &&
                        memory != null && !memory.relative && memory.index == null && memory.base != null && memory.width == 8 &&
                        memory.displacement in node.value..node.size - 8 && source != null && source.width == 8
            }
            val matches = stores.mapNotNull { store ->
                val values = ConstructorValues(flow.reaching(store.offset), emptyMap())
                val source = store.source as Register
                val tick = values.register(store.offset, source.number) as? Load ?: return@mapNotNull null
                val map = tick.base as? Load ?: return@mapNotNull null
                val owner = map.base as? Load ?: return@mapNotNull null
                if (map.member != playerMap || owner.base != Argument(7)) return@mapNotNull null
                require(tick.member in 0..mapSize - 8 && tick.member % 8 == 0L && arguments.any {
                    val load = flow.body.getValue(tick.site)
                    load.offset >= it.start && load.offset + load.size <= it.end
                }) { "Record tick is not a bounded typed construction argument" }
                require(owner.member in 0..consoleSize - 8 && owner.member % 8 == 0L)
                val memory = store.destination as Memory
                NativeAllocationResult.verify(flow, allocation, store, checkNotNull(memory.base))
                val field = memory.displacement - node.value
                require(
                    (field + 8 <= text || field >= text + textSize) &&
                            (field + 8 <= player || field >= player + indexWidth)
                ) { "Record tick overlaps another field" }
                ChatRecordTick(field, owner.member, playerMap, tick.member)
            }
            return matches.singleOrNull() ?: error("Record has no unique original Map tick copy")
        }
    }
}
