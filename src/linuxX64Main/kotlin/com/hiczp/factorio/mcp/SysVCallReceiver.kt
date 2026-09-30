package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Establishes a pointer member passed as receiver to the first direct call, without executing the caller. */
internal object SysVCallReceiver {
    private sealed interface Value
    private data class Input(val register: Int, val width: Int) : Value
    private data class Receiver(val offset: Long = 0) : Value
    private data class Pointer(val offset: Long) : Value
    private data class Stack(val offset: Long) : Value
    private data class Constant(val value: Long) : Value

    fun resolve(image: ElfImage, caller: String, callee: String, objectSize: Long): Long {
        val from = image.symbol(caller)
        val to = image.symbol(callee)
        EhFrames(image).function(from)
        EhFrames(image).function(to)
        return analyze(image.functionBytes(from, 256), from.address, to.address, objectSize)
    }

    fun analyze(bytes: BinaryView, address: Long, callee: Long, objectSize: Long): Long {
        require(bytes.size in 1..256 && address >= 0 && callee >= 0)
        val decoder = X64Instructions(bytes)
        val values = (0..15).associateWith<Int, Value> { Input(it, 8) }.toMutableMap()
        values[7] = Receiver()
        values[4] = Stack(0)
        val stack = mutableMapOf<Pair<Long, Int>, Value>()
        fun stackTop(): Long = (values[4] as? Stack)?.offset ?: error("Unknown call frame")
        fun stackAddress(memory: Memory): Long {
            require(!memory.relative && memory.index == null && memory.base != null)
            val base = values[memory.base] as? Stack ?: error("Call prefix writes outside its stack frame")
            val offset = base.offset + memory.displacement
            require(offset >= stackTop() && offset + memory.width <= 0) { "Call prefix exceeds its reserved frame" }
            return offset
        }

        fun read(register: Register): Value {
            val value = values.getValue(register.number)
            return if (value is Input) {
                require(register.width <= value.width)
                value.copy(width = register.width)
            } else {
                require(register.width == 8) { "Call prefix truncates pointer provenance" }
                value
            }
        }

        var offset = 0L
        var loads = 0
        while (offset < bytes.size) {
            val instruction = decoder.decode(offset)
            offset += instruction.size
            when (instruction.operation) {
                Operation.NOP, Operation.ENDBR -> Unit
                Operation.PUSH -> {
                    val register = instruction.destination as? Register ?: error("Unsupported call-prefix push")
                    require(register.width == 8)
                    val value = read(register)
                    val next = stackTop() - 8
                    require(next >= -4096)
                    values[4] = Stack(next)
                    stack[next to 8] = value
                }

                Operation.SUB -> {
                    require(instruction.destination == Register(4, 8)) { "Call prefix modifies a non-stack value" }
                    val count = (instruction.source as? Immediate)?.value ?: error("Nonconstant stack adjustment")
                    require(count in 0..4096 && count % 8 == 0L && stackTop() - count >= -4096)
                    values[4] = Stack(stackTop() - count)
                }

                Operation.MOV -> {
                    val source = instruction.source
                    val value = when (source) {
                        is Register -> read(source)
                        is Immediate -> Constant(source.value)
                        is Memory -> {
                            require(!source.relative && source.index == null && source.base != null)
                            when (val base = values[source.base]) {
                                is Receiver -> {
                                    require(source.width == 8 && ++loads == 1) { "Call receiver is not a single pointer load" }
                                    val member = base.offset + source.displacement
                                    require(objectSize >= 8 && member in 0..objectSize - 8)
                                    Pointer(member)
                                }

                                is Stack -> stack[stackAddress(source) to source.width]
                                    ?: error("Call prefix reloads an unproven stack value")

                                else -> error("Call prefix loads through an unproven receiver")
                            }
                        }

                        else -> error("Unsupported call-prefix source")
                    }
                    when (val target = instruction.destination) {
                        is Register -> {
                            require(target.number != 4 && (target.width == 8 || value is Input || value is Constant))
                            values[target.number] = if (value is Input) value.copy(width = target.width) else value
                        }

                        is Memory -> {
                            val address = stackAddress(target)
                            stack.keys.removeAll { (position, width) -> position < address + target.width && address < position + width }
                            stack[address to target.width] = value
                        }

                        else -> error("Unsupported call-prefix destination")
                    }
                }

                Operation.LEA -> {
                    val target = instruction.destination as? Register ?: error("Unsupported address destination")
                    val source = instruction.source as? Memory ?: error("Unsupported address expression")
                    require(target.width == 8 && target.number != 4 && !source.relative && source.index == null)
                    val base = values[source.base] as? Receiver ?: error("Address is not derived from the receiver")
                    val adjusted = base.offset + source.displacement
                    require(adjusted in 0..objectSize)
                    values[target.number] = Receiver(adjusted)
                }

                Operation.CALL -> {
                    val target = (instruction.destination as? Immediate)?.value ?: error("First call is indirect")
                    require(target >= -address && target <= Long.MAX_VALUE - address && address + target == callee) {
                        "First call does not identify the expected receiver type"
                    }
                    require((8 + stackTop()) % 16 == 0L && loads == 1) { "Unverified System V call frame" }
                    return (values[7] as? Pointer)?.offset
                        ?: error("First call receiver is not the proven pointer member")
                }

                else -> error("Unsupported control flow or mutation before the first call: ${instruction.operation}")
            }
        }
        error("No direct receiver call within analysis bound")
    }
}
