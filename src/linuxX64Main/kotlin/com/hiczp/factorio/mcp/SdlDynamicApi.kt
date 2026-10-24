package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/**
 * Named SDL API storage, connected to its initializer, forwarding ABI and selected implementation.
 */
internal data class SdlDynamicApi(val slot: Long, val implementation: Long) {
    companion object {
        fun resolve(image: ElfImage, name: String, arguments: List<Int>): SdlDynamicApi {
            require(name.startsWith("SDL_"))
            val initial = image.symbol("${name}_DEFAULT")
            require(initial.size in 1..1024) { "SDL forwarder exceeds complete-function bounds" }
            val implementation = image.symbol("${name}_REAL")
            val initialize = image.symbol("SDL_InitDynamicAPI")
            for (entry in listOf(initial, implementation, initialize)) EhFrames(image)
                .function(entry)
            val slot =
                analyze(
                    X64Instructions(image.functionBytes(initial, 1024)).all(128),
                    initial.address,
                    initialize.address,
                    arguments,
                )
            require(image.pointers.words(slot, 1).single().pointer() == initial.address) {
                "SDL API storage does not initially name its matching forwarder"
            }
            // Record the selected implementation as loaded-code evidence as well.
            image.functionBytes(implementation, 32768)
            return SdlDynamicApi(slot, implementation.address)
        }

        private sealed interface Value

        private data class Original(val register: Int) : Value

        private data class Frame(val offset: Long) : Value

        private data class Slot(val address: Long) : Value

        fun analyze(
            body: List<Instruction>,
            address: Long,
            initialize: Long,
            arguments: List<Int>,
        ): Long {
            require(body.isNotEmpty() && body.size <= 128 && body.first().offset == 0L)
            require(body.zipWithNext().all { (a, b) -> a.offset + a.size == b.offset })
            require(body.last().offset + body.last().size <= 1024)
            require(
                arguments.distinct() == arguments &&
                    arguments.all { it in listOf(7, 6, 2, 1, 8, 9) }
            )
            val registers = MutableList<Value?>(16) { Original(it) }
            registers[4] = Frame(0)
            val stack = mutableMapOf<Long, Value?>()
            var initialized = false
            fun frame(): Long =
                (registers[4] as? Frame)?.offset ?: error("Unproven SDL forwarder stack")
            fun local(memory: Memory): Long {
                require(memory.width == 8 && !memory.relative && memory.index == null)
                val base =
                    memory.base?.let { registers[it] } as? Frame
                        ?: error("Nonlocal SDL forwarder access")
                val offset = base.offset + memory.displacement
                require(offset in frame()..-8 && offset >= -1024 && offset % 8 == 0L)
                return offset
            }
            fun read(operand: Operand?, instruction: Instruction): Value? =
                when (operand) {
                    is Register -> {
                        require(operand.width == 8 && operand.number in 0..15)
                        registers[operand.number]
                    }
                    is Memory ->
                        if (operand.relative) {
                            require(initialized && operand.width == 8)
                            Slot(global(instruction, operand, address))
                        } else stack[local(operand)]
                    else -> error("Unsupported SDL forwarder operand")
                }
            for (instruction in body) {
                when (instruction.operation) {
                    Operation.PUSH -> {
                        val value = read(instruction.destination, instruction)
                        val next = frame() - 8
                        require(next >= -1024)
                        registers[4] = Frame(next)
                        stack[next] = value
                    }
                    Operation.POP -> {
                        val target =
                            instruction.destination as? Register ?: error("Invalid SDL pop")
                        require(target.width == 8 && target.number != 4)
                        val offset = frame()
                        require(offset in -1024..-8)
                        registers[target.number] = stack.remove(offset)
                        registers[4] = Frame(offset + 8)
                    }
                    Operation.MOV -> {
                        val value = read(instruction.source, instruction)
                        when (val target = instruction.destination) {
                            is Register -> {
                                require(target.width == 8)
                                registers[target.number] = value
                            }
                            is Memory -> stack[local(target)] = value
                            else -> error("Invalid SDL copy")
                        }
                    }
                    Operation.ADD,
                    Operation.SUB -> {
                        require(instruction.destination == Register(4, 8))
                        val amount =
                            (instruction.source as? Immediate)?.value ?: error("Variable SDL frame")
                        require(amount in 0..1024 && amount % 8 == 0L)
                        val next =
                            frame() +
                                if (instruction.operation == Operation.ADD) amount else -amount
                        require(next in -1024..0)
                        registers[4] = Frame(next)
                    }
                    Operation.CALL -> {
                        require(
                            !initialized &&
                                instruction.destination == Immediate(initialize - address)
                        )
                        require(frame() % 16 == -8L) { "SDL initializer stack is not SysV aligned" }
                        initialized = true
                        for (number in listOf(0, 1, 2, 6, 7, 8, 9, 10, 11)) registers[number] = null
                    }
                    Operation.JMP -> {
                        require(instruction == body.last() && initialized && frame() == 0L)
                        for (number in arguments + listOf(3, 5, 12, 13, 14, 15)) require(
                            registers[number] == Original(number)
                        ) {
                            "SDL forwarder changes an ABI input"
                        }
                        val slot =
                            read(instruction.destination, instruction) as? Slot
                                ?: error("SDL tail target is not API storage")
                        require(slot.address > 0 && slot.address % 8 == 0L)
                        return slot.address
                    }
                    Operation.NOP,
                    Operation.ENDBR -> Unit
                    else -> error("Unsupported SDL API forwarding instruction")
                }
            }
            error("SDL API forwarder has no verified tail dispatch")
        }

        fun global(instruction: Instruction, memory: Memory, address: Long): Long {
            require(memory.relative && memory.base == null && memory.index == null)
            require(
                address > 0 && address <= Long.MAX_VALUE - instruction.offset - instruction.size
            )
            val next = address + instruction.offset + instruction.size
            require(memory.displacement >= -next && memory.displacement <= Long.MAX_VALUE - next)
            return next + memory.displacement
        }

        fun calls(flow: X64ControlFlow, address: Long, slot: Long): List<Instruction> {
            val origins = OriginalPointerOrigins(flow, address)
            return flow.instructions.filter { instruction ->
                if (
                    instruction.offset !in flow.reachable || instruction.operation != Operation.CALL
                )
                    return@filter false
                when (val target = instruction.destination) {
                    is Memory ->
                        target.relative &&
                            target.width == 8 &&
                            global(instruction, target, address) == slot
                    is Register ->
                        target.width == 8 &&
                            (origins.register(instruction.offset, target.number)
                                    as? OriginalPointerOrigins.Global)
                                ?.address == slot
                    else -> false
                }
            }
        }
    }
}
