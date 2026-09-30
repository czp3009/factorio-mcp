package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.ConstructorValues.Argument
import com.hiczp.factorio.mcp.X64Instructions.*

/** Original optional Player index copied into the newly allocated console node, including its native null value. */
internal data class ChatRecordPlayer(val offset: Long, val absent: Long) {
    companion object {
        fun resolve(
            image: ElfImage,
            node: NativeListNodeLayout,
            text: Long,
            textSize: Long,
            index: PlayerIndex
        ): ChatRecordPlayer {
            val entry =
                image.symbol("_ZN13OutputConsole3addERK15LocalisedStringPK6PlayerRK13PrintSettingsOSt6vectorI25SavedSpecialItemReferenceSaISA_EE")
            val allocate = image.symbol("_Znwm")
            EhFrames(image).function(entry)
            EhFrames(image).function(allocate)
            return analyze(
                image.functionBytes(entry, 8192),
                entry.address,
                allocate.address,
                node,
                text,
                textSize,
                index
            )
        }

        fun analyze(
            bytes: BinaryView, address: Long, allocate: Long, node: NativeListNodeLayout,
            text: Long, textSize: Long, index: PlayerIndex
        ): ChatRecordPlayer {
            require(
                index.width in 1..2 && index.offset >= 0 && text >= 0 && textSize > 0 &&
                        node.value > 0 && node.size in node.value + 8..4096 && text + textSize <= node.size - node.value
            )
            require(bytes.size in 1..8192)
            val instructions = X64Instructions(bytes).all(2048)
            val flow = X64ControlFlow(instructions)
            val allocation = NativeAllocationResult.find(flow, allocate - address, node.size)
            val candidates = instructions.withIndex().filter { (_, instruction) ->
                val memory = instruction.destination as? Memory
                instruction.operation == Operation.MOV && memory != null && memory.width == index.width &&
                        memory.displacement in node.value..node.size - index.width && instruction.offset in flow.reachable
            }
            val store = candidates.singleOrNull() ?: error("Console node player field is absent or ambiguous")
            require(store.index >= 5)
            val memory = store.value.destination as Memory
            val output = store.value.source as? Register ?: error("Console index is not a scalar copy")
            require(!memory.relative && memory.index == null && memory.base != null && output.width == index.width)
            NativeAllocationResult.verify(flow, allocation, store.value, memory.base)
            val (test, branch, load, jump, empty) = instructions.subList(store.index - 5, store.index)
            val player = test.destination as? Register ?: error("Optional Player has no register guard")
            val values = ConstructorValues(flow.reaching(test.offset), emptyMap())
            require(
                test.operation == Operation.TEST && test.source == player && player.width == 8 &&
                        values.register(test.offset, player.number) == Argument(2)
            )
            require(
                branch.operation == Operation.JCC && branch.condition == 4 && branch.destination == Immediate(empty.offset) &&
                        load.operation == Operation.MOVZX && load.destination == Register(output.number, 4)
            )
            val source = load.source as? Memory ?: error("Player index has no source member")
            require(
                source.width == index.width && !source.relative && source.index == null && source.base == player.number &&
                        source.displacement == index.offset && jump.operation == Operation.JMP && jump.destination == Immediate(
                    store.value.offset
                )
            )
            require(empty.operation == Operation.MOV && empty.destination == output && empty.source is Immediate)
            require(
                flow.predecessors[branch.offset] == setOf(test.offset) &&
                        flow.predecessors[load.offset] == setOf(branch.offset) && flow.predecessors[jump.offset] == setOf(
                    load.offset
                ) &&
                        flow.predecessors[empty.offset] == setOf(branch.offset) &&
                        flow.predecessors[store.value.offset] == setOf(jump.offset, empty.offset)
            ) {
                "Console index selection has an alternate entry"
            }
            val field = memory.displacement - node.value
            require(field + index.width <= text || field >= text + textSize) { "Console player field overlaps its text" }
            return ChatRecordPlayer(field, (empty.source as Immediate).value and ((1L shl (index.width * 8)) - 1))
        }
    }
}
