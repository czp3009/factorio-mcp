package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.SysVReceiverFlow.*
import com.hiczp.factorio.mcp.X64Instructions.*

/** Establishes the two native entry arguments from a typed virtual event dispatch, not frame/output safety. */
internal object WidgetEventDispatch {
    data class Mask(val event: Long, val accepted: Long, val width: Int)

    fun verify(image: ElfImage, function: ElfImage.Symbol, widgetSize: Long, method: ItaniumVtable.Method) {
        EhFrames(image).function(function)
        require(function.size in 1..8192)
        verify(image.functionBytes(function, 8192), function.address, widgetSize, method.slot)
    }

    fun verify(bytes: BinaryView, address: Long, widgetSize: Long, slot: Int) {
        require(slot in 0..4095)
        val flow = SysVReceiverFlow(bytes, address, widgetSize)
        val candidates = flow.instructions.filter { instruction ->
            val target = instruction.destination as? Memory
            instruction.offset in flow.reachable && instruction.operation == Operation.CALL && target != null &&
                    !target.relative && target.index == null && target.width == 8 && target.displacement == slot * 8L
        }
        require(candidates.isNotEmpty()) { "Widget entry has no dispatch to its typed event method" }
        for (candidate in candidates) {
            val registers = flow.dispatchArguments(candidate.offset)
            val target = candidate.destination as Memory
            val table = target.base?.let { registers[it] } as? Pointer
            require(
                registers[7] == Receiver() && registers[6] == Original(6) &&
                        table?.base == Receiver() && table.offset == 0L
            ) {
                "Widget dispatch does not preserve its original receiver and event arguments"
            }
        }
    }

    /** Identifies a native event/Widget bitset gate; interpreting its bits requires the input-conversion proof. */
    fun mask(
        image: ElfImage, function: ElfImage.Symbol, widgetSize: Long, eventSize: Long,
        method: ItaniumVtable.Method
    ): Mask {
        EhFrames(image).function(function)
        require(function.size in 1..8192)
        return mask(image.functionBytes(function, 8192), function.address, widgetSize, eventSize, method.slot)
    }

    fun mask(bytes: BinaryView, address: Long, widgetSize: Long, eventSize: Long, slot: Int): Mask {
        require(eventSize in 1..256)
        verify(bytes, address, widgetSize, slot)
        val flow = SysVReceiverFlow(bytes, address, widgetSize)
        val instructions = flow.instructions.associateBy { it.offset }
        val calls = flow.instructions.filter { instruction ->
            val target = instruction.destination as? Memory
            instruction.offset in flow.reachable && instruction.operation == Operation.CALL && target != null &&
                    !target.relative && target.index == null && target.width == 8 && target.displacement == slot * 8L
        }

        fun defines(instruction: X64Instructions.Instruction, register: Int): Boolean {
            if (instruction.operation == Operation.CALL) return register in listOf(0, 1, 2, 6, 7, 8, 9, 10, 11)
            if (instruction.operation in listOf(
                    Operation.CMP, Operation.TEST, Operation.JMP, Operation.JCC,
                    Operation.NOP, Operation.ENDBR, Operation.RET, Operation.PUSH
                )
            ) return false
            return (instruction.destination as? Register)?.number == register ||
                    instruction.operation == Operation.XCHG && (instruction.source as? Register)?.number == register
        }

        fun definition(before: Long, register: Int): X64Instructions.Instruction {
            val pending = ArrayDeque<Long>()
            pending.addAll(flow.predecessors[before].orEmpty())
            val visited = mutableSetOf<Long>()
            val found = mutableSetOf<Long>()
            while (pending.isNotEmpty()) {
                val offset = pending.removeFirst()
                if (!visited.add(offset)) continue
                val instruction = instructions.getValue(offset)
                if (defines(instruction, register)) found += offset
                else {
                    require(offset != 0L) { "Event mask comes from an uninitialized register" }
                    pending.addAll(flow.predecessors[offset].orEmpty())
                }
            }
            return instructions[found.singleOrNull()] ?: error("Event mask has ambiguous reaching definitions")
        }

        fun argumentField(before: Long, register: Register): Long {
            var position = before
            var current = register
            repeat(32) {
                val load = definition(position, current.number)
                require(
                    load.operation in listOf(
                        Operation.MOV,
                        Operation.MOVZX
                    )
                ) { "Event mask is transformed before testing" }
                val destination = load.destination as? Register ?: error("Event mask is not loaded into a register")
                require(destination.width >= register.width)
                when (val source = load.source) {
                    is Register -> {
                        require(source.width >= register.width)
                        current = source
                        position = load.offset
                    }

                    is Memory -> {
                        require(source.width == register.width && !source.relative && source.index == null)
                        val registers = flow.before(load.offset)
                        require(source.base?.let { registers[it] } == Original(6)) {
                            "Event mask does not come from the original event argument"
                        }
                        require(source.displacement in 0..eventSize - source.width)
                        return source.displacement
                    }

                    else -> error("Event mask is not an argument member")
                }
            }
            error("Event mask register-copy chain exceeds bound")
        }

        val candidates = mutableListOf<Mask>()
        for (test in flow.instructions) {
            if (test.offset !in flow.reachable || test.operation != Operation.TEST) continue
            val memory = (test.destination as? Memory) ?: (test.source as? Memory) ?: continue
            val register = (test.destination as? Register) ?: (test.source as? Register) ?: continue
            if (memory.relative || memory.index != null || memory.width != register.width || register.number == 4) continue
            var previous = test.offset
            var branch = instructions[test.offset + test.size] ?: continue
            var singleProducer = true
            while (branch.operation == Operation.NOP || branch.operation == Operation.ENDBR) {
                singleProducer = singleProducer && flow.predecessors[branch.offset] == setOf(previous)
                previous = branch.offset
                branch = instructions[branch.offset + branch.size] ?: error("Event mask guard leaves the function")
            }
            if (branch.operation != Operation.JCC || branch.condition !in listOf(4, 5)) continue
            val target = (branch.destination as? Immediate)?.value ?: error("Indirect event mask branch")
            val nonzero = if (branch.condition == 4) branch.offset + branch.size else target
            if (nonzero !in flow.reachable || !calls.all {
                    flow.requiresEdge(
                        it.offset,
                        branch.offset,
                        nonzero
                    )
                }) continue
            require(singleProducer && flow.predecessors[branch.offset] == setOf(previous)) {
                "Event mask branch has another flag producer"
            }
            val registers = flow.before(test.offset)
            val owner = memory.base?.let { registers[it] } as? Receiver ?: continue
            val member = owner.adjustment + memory.displacement
            require(member in 0..widgetSize - memory.width)
            candidates += Mask(argumentField(test.offset, register), member, memory.width)
        }
        return candidates.distinct().singleOrNull() ?: error("Widget dispatch does not establish one event bitset gate")
    }
}
