package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Allocation/constructor/publication evidence, obtained without executing the factory. */
internal data class GlobalAllocation(val size: Long, val prefixSize: Int)

/** Proves an entry prefix that constructs one complete heap object and publishes its unchanged pointer. */
internal object SysVGlobalAllocation {
    private sealed interface Value
    private data class Input(val register: Int, val width: Int = 8, val adjustment: Long = 0) : Value
    private data class Stack(val offset: Long) : Value
    private data class Constant(val value: Long) : Value
    private data object Allocation : Value
    private data object Unknown : Value

    fun resolve(
        image: ElfImage,
        factory: String,
        constructor: String,
        global: String,
        allocator: String = "_Znwm",
    ): GlobalAllocation {
        val caller = image.symbol(factory)
        val construct = image.symbol(constructor)
        val allocate = image.symbol(allocator)
        val destination = image.symbol(global)
        require(destination.type == 1 && destination.size == 8L) { "Expected a pointer-sized global object symbol" }
        require(image.segments.count {
            it.type == 1L && it.flags and 2L != 0L && destination.address >= it.address &&
                    destination.address - it.address <= it.memorySize && 8 <= it.memorySize - (destination.address - it.address)
        } == 1) {
            "Global pointer is outside a writable ELF load segment"
        }
        for (function in listOf(caller, construct, allocate)) EhFrames(image).function(function)
        return analyze(
            image.functionBytes(caller, 512),
            caller.address,
            allocate.address,
            construct.address,
            destination.address
        )
    }

    fun analyze(bytes: BinaryView, address: Long, allocator: Long, constructor: Long, global: Long): GlobalAllocation {
        require(
            bytes.size in 1..512 && address >= 0 && address <= Long.MAX_VALUE - bytes.size &&
                    allocator >= 0 && constructor >= 0 && global in 0..Long.MAX_VALUE - 8 && allocator != constructor
        )
        val decoder = X64Instructions(bytes)
        val registers = (0..15).associateWith<Int, Value> { Input(it) }.toMutableMap()
        registers[4] = Stack(0)
        val stack = mutableMapOf<Pair<Long, Int>, Value>()
        val volatile = setOf(0, 1, 2, 6, 7, 8, 9, 10, 11)
        var allocationSize: Long? = null
        var constructed = false
        fun stackTop() = (registers[4] as? Stack)?.offset ?: error("Unknown allocation frame")
        fun stackAddress(memory: Memory): Long {
            require(!memory.relative && memory.index == null && memory.base != null)
            val base = registers[memory.base] as? Stack ?: error("Allocation prefix accesses unknown memory")
            val position = base.offset + memory.displacement
            require(position >= stackTop() && position <= -memory.width) { "Allocation prefix exceeds its reserved frame" }
            return position
        }

        fun read(register: Register): Value {
            val value = registers.getValue(register.number)
            return when (value) {
                is Input -> {
                    require(register.width <= value.width && (value.adjustment == 0L || register.width == 8))
                    value.copy(width = register.width)
                }

                is Constant -> Constant(if (register.width == 8) value.value else value.value and ((1L shl (register.width * 8)) - 1))
                else -> {
                    require(register.width == 8 && value != Unknown) { "Allocation prefix loses pointer provenance" }
                    value
                }
            }
        }

        fun write(register: Register, value: Value) {
            require(register.width in listOf(1, 2, 4, 8))
            require(register.width == 8 || value is Input || value is Constant) { "Allocation prefix truncates a pointer" }
            registers[register.number] = when {
                register.width < 4 -> Unknown // Upper bits were not established by this write.
                value is Constant && register.width == 4 -> Constant(value.value and 0xffffffffL)
                value is Input && register.width == 4 -> value.copy(width = 4)
                else -> value
            }
        }

        var offset = 0L
        while (offset < bytes.size) {
            val instruction = decoder.decode(offset)
            offset += instruction.size
            when (instruction.operation) {
                Operation.NOP, Operation.ENDBR -> Unit
                Operation.PUSH -> {
                    val register = instruction.destination as? Register ?: error("Unsupported allocation frame push")
                    require(register.width == 8)
                    val value = read(register)
                    val next = stackTop() - 8
                    require(next >= -16384)
                    registers[4] = Stack(next)
                    stack[next to 8] = value
                }

                Operation.SUB -> {
                    require(instruction.destination == Register(4, 8)) { "Allocation prefix changes an object pointer" }
                    val count = (instruction.source as? Immediate)?.value ?: error("Dynamic allocation frame")
                    require(count in 0..16384 && count % 8 == 0L && stackTop() - count >= -16384)
                    registers[4] = Stack(stackTop() - count)
                }

                Operation.MOV -> {
                    val source = when (val operand = instruction.source) {
                        is Register -> read(operand)
                        is Immediate -> Constant(operand.value)
                        is Memory -> stack[stackAddress(operand) to operand.width]
                            ?: error("Unproven allocation frame load")

                        else -> error("Unsupported allocation move")
                    }
                    when (val target = instruction.destination) {
                        is Register -> write(target, source)
                        is Memory -> {
                            if (target.relative) {
                                require(target.base == null && target.index == null)
                                val next = address + offset
                                require(target.displacement >= -next && target.displacement <= Long.MAX_VALUE - next)
                                val destination = next + target.displacement
                                require(destination <= Long.MAX_VALUE - target.width)
                                if (destination < global + 8 && global < destination + target.width) {
                                    require(destination == global && target.width == 8 && source == Allocation && constructed) {
                                        "Global publication is not the complete constructed allocation"
                                    }
                                    return GlobalAllocation(checkNotNull(allocationSize), offset.toInt())
                                }
                                require(source is Constant) { "Allocation escapes through another global" }
                            } else {
                                val position = stackAddress(target)
                                stack.keys.removeAll { (start, width) -> start < position + target.width && position < start + width }
                                require(target.width == 8 || source is Input || source is Constant)
                                stack[position to target.width] = source
                            }
                        }

                        else -> error("Unsupported allocation destination")
                    }
                }

                Operation.LEA -> {
                    val target = instruction.destination as? Register ?: error("Unsupported allocation address target")
                    val source = instruction.source as? Memory ?: error("Unsupported allocation address")
                    require(target.width == 8 && !source.relative && source.index == null && source.base != null)
                    val value =
                        registers[source.base] as? Input ?: error("Allocation address changes the allocated object")
                    require(
                        value.width == 8 && source.displacement in -16384..16384 &&
                                value.adjustment + source.displacement in -16384..16384
                    )
                    write(target, value.copy(adjustment = value.adjustment + source.displacement))
                }

                Operation.XOR -> {
                    val target = instruction.destination as? Register ?: error("Unsupported allocation XOR")
                    require(target == instruction.source && target.width in listOf(4, 8))
                    write(target, Constant(0))
                }

                Operation.CALL -> {
                    val relative = (instruction.destination as? Immediate)?.value ?: error("Indirect allocation call")
                    require(relative >= -address && relative <= Long.MAX_VALUE - address)
                    val target = address + relative
                    require((8 + stackTop()) % 16 == 0L) { "Unaligned System V allocation call frame" }
                    require(volatile.none { registers[it] is Stack }) { "Allocation call could modify the saved frame" }
                    when (target) {
                        allocator -> {
                            require(allocationSize == null && !constructed) { "Multiple allocation candidates" }
                            val size =
                                (registers[7] as? Constant)?.value ?: error("Nonconstant complete-object allocation")
                            require(size in 1..(64 * 1024 * 1024)) { "Complete-object allocation exceeds bound" }
                            allocationSize = size
                        }

                        constructor -> {
                            require(allocationSize != null && !constructed && registers[7] == Allocation) {
                                "Constructor receiver is not the unchanged allocation"
                            }
                            require(listOf(6, 2, 1, 8, 9).none { registers[it] == Allocation }) {
                                "Allocation aliases a constructor argument"
                            }
                            constructed = true
                        }

                        else -> error("Unexpected call before global publication")
                    }
                    for (register in volatile) registers[register] = Unknown
                    if (target == allocator) registers[0] = Allocation
                }

                else -> error("Unsupported control flow before global publication: ${instruction.operation}")
            }
        }
        error("No constructed-object publication in bounded factory prefix")
    }
}
