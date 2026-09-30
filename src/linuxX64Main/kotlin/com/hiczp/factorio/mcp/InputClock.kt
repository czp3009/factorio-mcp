@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlinx.cinterop.sizeOf
import platform.posix.timeval

/** ABI and bounded effects of the concrete input clock, including both native initialization states. */
internal object InputClock {
    data class Layout(val initialized: Long, val origin: Long, val timeExtent: Int)
    data class Literal(val address: Long, val bits: Long)
    data class Proof(val layout: Layout, val literals: List<Literal>)
    private sealed interface Value
    private data class Original(val register: Int) : Value
    private data class Stack(val offset: Long) : Value
    private data class Global(val address: Long) : Value
    private data class Number(val value: Long) : Value
    private data class IntegerTime(val local: Boolean) : Value
    private data class FloatingTime(val local: Boolean) : Value
    private data object Unknown : Value

    fun resolve(
        image: ElfImage, name: String = "_ZNK16InputHandlerAgui7getTimeEv",
        flagName: String = "ticks_started", originName: String = "start_tv"
    ): Proof {
        val function = image.symbol(name)
        EhFrames(image).function(function)
        require(function.size in 1..2048)
        val flag = image.symbol(flagName)
        val origin = image.symbol(originName)
        val extent = sizeOf<timeval>().toInt()
        for ((symbol, size) in listOf(flag to 1, origin to extent)) {
            require(symbol.type == 1 && symbol.size == size.toLong() && image.sections.any { section ->
                section.flags and 3L == 3L && symbol.address >= section.address && size <= section.size &&
                        symbol.address - section.address <= section.size - size
            }) { "Input clock storage has incompatible bounds or allocation" }
        }
        return analyze(
            image.functionBytes(function, 2048), function.address, Layout(flag.address, origin.address, extent),
            { image.importedFunction(it) == "gettimeofday" }) { address ->
            require(image.sections.any { section ->
                section.flags and 3L == 2L && address >= section.address && section.size >= 8 &&
                        address - section.address <= section.size - 8
            }) { "Input clock divisor is not read-only data" }
            image.virtualBytes(address, 8).unsigned(0, 8)
        }
    }

    fun analyze(
        bytes: BinaryView, address: Long, layout: Layout,
        timeCall: (Long) -> Boolean, literal: (Long) -> Long
    ): Proof {
        require(bytes.size in 1..2048 && address >= 0 && address <= Long.MAX_VALUE - bytes.size)
        require(
            layout.initialized > 0 && layout.origin > 0 && layout.timeExtent == sizeOf<timeval>().toInt() &&
                    layout.origin <= Long.MAX_VALUE - layout.timeExtent &&
                    layout.initialized !in layout.origin until layout.origin + layout.timeExtent
        )
        val body = X64Instructions(bytes, allowWideMultiply = true).all(512).associateBy { it.offset }
        val literals = mutableSetOf<Literal>()
        for (initiallyStarted in listOf(false, true)) {
            val registers = MutableList<Value>(32) { Original(it) }
            registers[4] = Stack(0)
            val saved = mutableMapOf<Pair<Long, Int>, Value>()
            val clockBytes = mutableSetOf<Long>()
            var started = initiallyStarted
            var flagWrites = 0
            var localCalls = 0
            var originCalls = 0
            var comparison: Pair<Long, Long>? = null
            var position = 0L
            var returned = false
            var steps = 0
            while (!returned) {
                require(++steps <= 512) { "Input clock control flow exceeds bound" }
                val instruction = body[position] ?: error("Input clock control flow leaves its function")
                val next = position + instruction.size
                fun top() = (registers[4] as? Stack)?.offset ?: error("Input clock has an unproven frame")
                fun pointer(memory: Memory): Value {
                    require(memory.index == null)
                    if (memory.relative) {
                        val end = address + next
                        require(
                            memory.base == null && memory.displacement >= -end &&
                                    memory.displacement <= Long.MAX_VALUE - end
                        )
                        return Global(end + memory.displacement)
                    }
                    return when (val base = memory.base?.let { registers[it] }) {
                        is Stack -> {
                            require(memory.displacement in -4096..4096)
                            Stack(base.offset + memory.displacement)
                        }

                        is Global -> {
                            require(memory.displacement >= -base.address && memory.displacement <= Long.MAX_VALUE - base.address)
                            Global(base.address + memory.displacement)
                        }

                        else -> error("Input clock reads an unverified argument or pointer")
                    }
                }

                fun read(operand: X64Instructions.Operand?): Value = when (operand) {
                    is Immediate -> Number(operand.value)
                    is Register -> when (val value = registers[operand.number]) {
                        is Number -> Number(
                            if (operand.width < 8) value.value and ((1L shl (operand.width * 8)) - 1)
                            else value.value
                        )

                        is Stack, is Global, is Original -> if (operand.width == 8) value else Unknown
                        else -> value
                    }

                    is Memory -> when (val location = pointer(operand)) {
                        is Stack -> {
                            require(location.offset >= top() && location.offset <= -operand.width)
                            saved[location.offset to operand.width] ?: run {
                                require((0 until operand.width).all { location.offset + it in clockBytes }) {
                                    "Input clock reads uninitialized or partially overwritten local storage"
                                }
                                IntegerTime(true)
                            }
                        }

                        is Global -> when {
                            location.address == layout.initialized && operand.width == 1 -> Number(if (started) 1 else 0)
                            location.address >= layout.origin && operand.width <= layout.timeExtent &&
                                    location.address - layout.origin <= layout.timeExtent - operand.width -> {
                                require(started && (initiallyStarted || originCalls == 1)) { "Input clock reads an uninitialized origin" }
                                IntegerTime(false)
                            }

                            else -> error("Input clock reads an unrelated global")
                        }

                        else -> error("Invalid input clock memory")
                    }

                    else -> error("Missing input clock operand")
                }

                fun stackWrite(offset: Long, width: Int, value: Value) {
                    require(offset >= top() && offset <= -width)
                    require(saved.none { (slot, old) ->
                        (old is Original || old is Stack) && slot.first < offset + width && offset < slot.first + slot.second &&
                                (slot.first != offset || slot.second != width)
                    }) { "Input clock partially overwrites a saved register" }
                    saved.keys.removeAll { (start, length) -> start < offset + width && offset < start + length }
                    clockBytes.removeAll { it in offset until offset + width }
                    saved[offset to width] = value
                }

                fun write(destination: X64Instructions.Operand?, value: Value) {
                    when (destination) {
                        is Register -> {
                            if (destination.number == 4) require(destination.width == 8 && value is Stack && value.offset in -4096..0)
                            require(destination.width == 8 || value !is Stack && value !is Global && value !is Original)
                            registers[destination.number] = if (destination.width == 4 && value is Number)
                                Number(value.value and 0xffffffffL) else value
                        }

                        is Memory -> when (val location = pointer(destination)) {
                            is Stack -> stackWrite(location.offset, destination.width, value)
                            is Global -> {
                                require(
                                    location.address == layout.initialized && destination.width == 1 && value == Number(
                                        1
                                    ) &&
                                            !started && !initiallyStarted && ++flagWrites == 1
                                ) {
                                    "Input clock performs an unrelated global write"
                                }
                                started = true
                            }

                            else -> error("Invalid input clock write")
                        }

                        else -> error("Missing input clock destination")
                    }
                }

                fun scalar(value: Value): Boolean = when (value) {
                    is Number -> false
                    is IntegerTime -> value.local
                    else -> error("Input clock arithmetic consumes an unverified argument")
                }

                val oldComparison = comparison
                comparison = null
                position = next
                when (instruction.operation) {
                    Operation.NOP, Operation.ENDBR -> comparison = oldComparison
                    Operation.PUSH -> {
                        val value = read(instruction.destination)
                        val offset = top() - 8
                        require(offset >= -4096)
                        registers[4] = Stack(offset)
                        stackWrite(offset, 8, value)
                        comparison = oldComparison
                    }

                    Operation.POP -> {
                        val offset = top()
                        val value = saved.remove(offset to 8) ?: error("Input clock restores an unproven register")
                        require(instruction.destination is Register && instruction.destination.number != 4)
                        write(instruction.destination, value)
                        registers[4] = Stack(offset + 8)
                        comparison = oldComparison
                    }

                    Operation.MOV, Operation.MOVZX -> {
                        write(instruction.destination, read(instruction.source))
                        comparison = oldComparison
                    }

                    Operation.LEA -> {
                        val source = instruction.source as? Memory ?: error("Invalid input clock address")
                        val value = pointer(source)
                        require(value !is Stack || value.offset in top()..0)
                        require(value !is Global || value.address == layout.origin)
                        write(instruction.destination, value)
                        comparison = oldComparison
                    }

                    Operation.ADD, Operation.SUB -> {
                        if (instruction.destination == Register(4, 8)) {
                            val amount =
                                (instruction.source as? Immediate)?.value ?: error("Variable input clock frame")
                            require(amount in 0..4096 && amount % 8 == 0L)
                            val offset = top() + if (instruction.operation == Operation.ADD) amount else -amount
                            require(offset in -4096..0)
                            registers[4] = Stack(offset)
                            saved.keys.removeAll { it.first < offset }
                            clockBytes.removeAll { it < offset }
                        } else {
                            val left = scalar(read(instruction.destination))
                            val right = scalar(read(instruction.source))
                            write(instruction.destination, IntegerTime(left || right))
                        }
                    }

                    Operation.XOR -> {
                        require(instruction.destination is Register && instruction.destination == instruction.source)
                        write(instruction.destination, Number(0))
                    }

                    Operation.SHR, Operation.SAR, Operation.SHL -> {
                        val value = scalar(read(instruction.destination))
                        require(instruction.source is Immediate && instruction.source.value in 0..63)
                        write(instruction.destination, IntegerTime(value))
                    }

                    Operation.MULTIPLY_IMMEDIATE -> {
                        require(instruction.immediate != null)
                        write(instruction.destination, IntegerTime(scalar(read(instruction.source))))
                    }

                    Operation.MULTIPLY -> {
                        val left = scalar(read(instruction.destination))
                        val right = scalar(read(instruction.source))
                        write(instruction.destination, IntegerTime(left || right))
                    }

                    Operation.MULTIPLY_WIDE -> {
                        val accumulator = scalar(registers[0])
                        val multiplier = scalar(read(instruction.source))
                        registers[0] = IntegerTime(accumulator || multiplier)
                        registers[2] = IntegerTime(accumulator || multiplier)
                    }

                    Operation.CMP, Operation.TEST -> {
                        val left = read(instruction.destination) as? Number
                            ?: error("Input clock guard is not its initialization flag")
                        val right =
                            read(instruction.source) as? Number ?: error("Input clock guard has an unknown operand")
                        comparison = if (instruction.operation == Operation.TEST) (left.value and right.value) to 0
                        else left.value to right.value
                    }

                    Operation.JCC -> {
                        val (left, right) = checkNotNull(oldComparison) { "Input clock branch has unproven flags" }
                        require(instruction.condition in listOf(4, 5))
                        val taken = if (instruction.condition == 4) left == right else left != right
                        val target =
                            (instruction.destination as? Immediate)?.value ?: error("Indirect input clock branch")
                        require(target > instruction.offset && target in body)
                        if (taken) position = target
                    }

                    Operation.JMP -> {
                        val target =
                            (instruction.destination as? Immediate)?.value ?: error("Indirect input clock jump")
                        require(target > instruction.offset && target in body)
                        position = target
                    }

                    Operation.CALL -> {
                        val relative =
                            (instruction.destination as? Immediate)?.value ?: error("Indirect input clock call")
                        require(relative >= -address && relative <= Long.MAX_VALUE - address && timeCall(address + relative)) {
                            "Input clock calls an unverified function"
                        }
                        require((top() + 8) % 16 == 0L && registers[6] == Number(0))
                        when (val output = registers[7]) {
                            is Global -> {
                                require(output.address == layout.origin && !initiallyStarted && flagWrites == 1 && ++originCalls == 1)
                            }

                            is Stack -> {
                                require(started && (initiallyStarted || originCalls == 1 && flagWrites == 1)) {
                                    "Input clock samples before initializing its origin"
                                }
                                require(output.offset >= top() && output.offset <= -layout.timeExtent && ++localCalls == 1)
                                require(saved.none { (slot, value) ->
                                    slot.first < output.offset + layout.timeExtent && output.offset < slot.first + slot.second &&
                                            (value is Original || value is Stack || value is Global)
                                }) { "Time output overlaps a saved pointer or register" }
                                saved.keys.removeAll { (start, size) ->
                                    start < output.offset + layout.timeExtent && output.offset < start + size
                                }
                                clockBytes.addAll((0 until layout.timeExtent).map { output.offset + it })
                            }

                            else -> error("Input clock exposes an unverified output pointer")
                        }
                        for (register in listOf(0, 1, 2, 6, 7, 8, 9, 10, 11) + (16..31)) registers[register] = Unknown
                    }

                    Operation.INT_TO_DOUBLE -> {
                        val source = read(instruction.source)
                        write(instruction.destination, FloatingTime(scalar(source)))
                    }

                    Operation.DOUBLE_DIVIDE -> {
                        val value = read(instruction.destination) as? FloatingTime
                            ?: error("Input clock returns non-floating data")
                        val source =
                            instruction.source as? Memory ?: error("Input clock divisor lacks a read-only location")
                        require(source.relative && source.width == 8)
                        val location = (pointer(source) as Global).address
                        val bits = literal(location)
                        val divisor = Double.fromBits(bits)
                        require(divisor.isFinite() && divisor > 0) { "Input clock divisor is not finite and positive" }
                        require(literals.none { it.address == location && it.bits != bits }) { "Input clock literal changed during analysis" }
                        literals += Literal(location, bits)
                        write(instruction.destination, value)
                    }

                    Operation.RET -> {
                        require(top() == 0L && listOf(3, 5, 12, 13, 14, 15).all { registers[it] == Original(it) }) {
                            "Input clock does not restore its frame and preserved registers"
                        }
                        require(
                            registers[16] == FloatingTime(true) && started && localCalls == 1 &&
                                    originCalls == (if (initiallyStarted) 0 else 1) && flagWrites == (if (initiallyStarted) 0 else 1)
                        ) {
                            "Input clock has an unproven return or initialization path"
                        }
                        returned = true
                    }

                    else -> error("Unsupported input clock operation: ${instruction.operation}")
                }
            }
        }
        return Proof(layout, literals.sortedBy { it.address })
    }
}
