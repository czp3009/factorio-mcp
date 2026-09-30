package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** A complete constant string constructed in a private frame before a named native consumer. Never executes code. */
internal object LocalStringLiteral {
    data class Result(val text: String, val consumer: Long)
    private sealed interface Value
    private data class Pointer(val region: Int, val offset: Long) : Value
    private data class Bytes(val bytes: List<Byte>) : Value

    fun analyze(
        flow: X64ControlFlow, start: Long, address: Long, string: NativeStringLayout,
        allocate: Long, consume: Long, read: (Long, Int) -> ByteArray
    ): Result {
        require(
            string.size in 1..256 && string.data in 0..string.size - 8 &&
                    string.length in 0..string.size - 8 && string.local in 0 until string.size
        )
        val frame = SysVLocalArgument(flow).registers(start)
        val bottom = checkNotNull(frame[4])
        val registers = MutableList<Value?>(32) { frame[it]?.let { offset -> Pointer(0, offset) } }
        val bytes = mutableMapOf<Pointer, Byte>()
        val pointers = mutableMapOf<Pointer, Pointer>()
        var heapSize: Long? = null
        val seen = mutableSetOf<Long>()
        var site = start

        fun span(pointer: Pointer, width: Int) {
            require(width in 1..256)
            when (pointer.region) {
                0 -> require(pointer.offset >= bottom && pointer.offset <= -width)
                1 -> require(pointer.offset >= 0 && pointer.offset <= checkNotNull(heapSize) - width)
                else -> error("Literal storage is neither private frame nor its own allocation")
            }
        }

        fun constant(number: Long, width: Int) = Bytes(List(width) { (number ushr (it * 8)).toByte() })
        fun integer(value: Value?, width: Int): Long {
            require(value is Bytes && width in 1..8 && value.bytes.size >= width)
            return (0 until width).fold(0L) { result, index ->
                result or ((value.bytes[index].toLong() and 255) shl (index * 8))
            }
        }

        fun location(memory: Memory): Pointer {
            require(!memory.relative && memory.index == null && memory.displacement in -16384..16384)
            val base = memory.base?.let { registers[it] } as? Pointer ?: error("Literal memory has no private owner")
            return base.copy(offset = base.offset + memory.displacement)
        }

        fun load(pointer: Pointer, width: Int): Value? {
            span(pointer, width)
            if (width == 8) pointers[pointer]?.let { return it }
            val result = (0 until width).map { bytes[pointer.copy(offset = pointer.offset + it)] }
            return if (result.all { it != null }) Bytes(result.filterNotNull()) else null
        }

        fun readValue(operand: Operand?): Value? = when (operand) {
            is Immediate -> constant(operand.value, 8)
            is Register -> when (val value = registers[operand.number]) {
                is Pointer -> value.also { require(operand.width == 8) }
                is Bytes -> value.takeIf { it.bytes.size >= operand.width }?.let { Bytes(it.bytes.take(operand.width)) }
                null -> null
            }

            is Memory -> if (operand.relative) {
                require(operand.base == null && operand.index == null && operand.width in listOf(1, 2, 4, 8, 16))
                val instruction = flow.body.getValue(site)
                Bytes(read(address + site + instruction.size + operand.displacement, operand.width).toList())
            } else load(location(operand), operand.width)

            else -> error("Unsupported literal input")
        }

        fun write(operand: Operand?, value: Value?) {
            when (operand) {
                is Register -> {
                    require(operand.number != 4 && operand.width in listOf(4, 8, 16))
                    registers[operand.number] = when (value) {
                        is Pointer -> value.also { require(operand.width == 8) }
                        is Bytes -> {
                            require(value.bytes.size >= operand.width)
                            val result = value.bytes.take(operand.width)
                            Bytes(if (operand.number < 16 && operand.width == 4) result + List(4) { 0.toByte() } else result)
                        }

                        null -> null
                    }
                }

                is Memory -> {
                    val target = location(operand)
                    span(target, operand.width)
                    pointers.keys.filter {
                        it.region == target.region && it.offset < target.offset + operand.width &&
                                target.offset < it.offset + 8
                    }.forEach { pointers.remove(it) }
                    for (index in 0 until operand.width) bytes.remove(target.copy(offset = target.offset + index))
                    when (value) {
                        is Pointer -> {
                            require(operand.width == 8)
                            pointers[target] = value
                        }

                        is Bytes -> {
                            require(value.bytes.size >= operand.width)
                            for (index in 0 until operand.width)
                                bytes[target.copy(offset = target.offset + index)] = value.bytes[index]
                        }

                        null -> Unit
                    }
                }

                else -> error("Unsupported literal destination")
            }
        }
        repeat(64) {
            require(seen.add(site)) { "Literal construction loops" }
            val instruction = flow.body[site] ?: error("Literal construction leaves its body")
            when (instruction.operation) {
                Operation.NOP -> Unit
                Operation.MOV, Operation.VECTOR_MOV, Operation.SCALAR_MOV -> write(
                    instruction.destination,
                    readValue(instruction.source)
                )

                Operation.LEA -> write(
                    instruction.destination,
                    location(instruction.source as? Memory ?: error("Literal address has no memory expression"))
                )

                Operation.XOR -> {
                    require(instruction.destination == instruction.source && instruction.destination is Register)
                    write(instruction.destination, constant(0, instruction.destination.width))
                }

                Operation.ADD, Operation.SUB -> {
                    val target = instruction.destination as? Register ?: error("Literal arithmetic writes memory")
                    val amount =
                        (instruction.source as? Immediate)?.value ?: error("Literal adjustment is not constant")
                    require(amount in -256..256)
                    val delta = if (instruction.operation == Operation.ADD) amount else -amount
                    val old = readValue(target)
                    write(
                        target, when (old) {
                            is Pointer -> old.copy(offset = old.offset + delta)
                            is Bytes -> constant(integer(old, target.width) + delta, target.width)
                            null -> error("Literal adjustment has no known value")
                        }
                    )
                }

                Operation.JMP -> {
                    site = (instruction.destination as? Immediate)?.value ?: error("Indirect literal path")
                    return@repeat
                }

                Operation.CALL -> {
                    val callee = (instruction.destination as? Immediate)?.value ?: error("Indirect literal consumer")
                    if (callee == consume) {
                        val output = registers[7] as? Pointer ?: error("Literal consumer has no string receiver")
                        require(output.region == 0 && integer(registers[6], 8) == 0L && integer(registers[2], 8) == 0L)
                        span(output, string.size.toInt())
                        val data = load(output.copy(offset = output.offset + string.data), 8) as? Pointer
                            ?: error("Literal string has no initialized data pointer")
                        val length = integer(load(output.copy(offset = output.offset + string.length), 8), 8)
                        require(length in 1..128)
                        if (data.region == 0) require(
                            data == output.copy(offset = output.offset + string.local) &&
                                    string.local + length < string.size
                        )
                        else require(data == Pointer(1, 0) && heapSize != null && length < checkNotNull(heapSize))
                        val content =
                            load(data, length.toInt() + 1) as? Bytes ?: error("Literal string has unknown bytes")
                        require(
                            content.bytes.last() == 0.toByte() && content.bytes.dropLast(1).none { it == 0.toByte() })
                        return Result(
                            content.bytes.dropLast(1).toByteArray().decodeToString(throwOnInvalidSequence = true), site
                        )
                    }
                    require(callee == allocate && heapSize == null) { "Literal construction calls an unsupported entry" }
                    heapSize = integer(registers[7], 8).also { require(it in 1..256) }
                    for (register in listOf(0, 1, 2, 6, 7, 8, 9, 10, 11) + (16..31)) registers[register] = null
                    registers[0] = Pointer(1, 0)
                }

                else -> error("Unsupported literal construction: ${instruction.operation}")
            }
            site += instruction.size
        }
        error("Literal construction exceeds bound")
    }
}
