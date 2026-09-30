package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.SysVReceiverFlow.*
import com.hiczp.factorio.mcp.X64Instructions.*

/** Native selection of the first pointer in a nonempty range, guarded by its exact string contents. */
internal data class FirstNamedPointer(val begin: Long, val end: Long, val name: Long, val expected: List<Byte>)

internal object SysVFirstNamedPointer {
    private sealed interface Value
    private data object Object : Value
    private data object Data : Value
    private data object Unknown : Value
    private data class Constant(val value: Long) : Value
    private data class Bytes(val offset: Int, val width: Int) : Value
    private data class Mismatch(val bytes: Map<Int, Byte>) : Value

    fun resolve(
        bytes: BinaryView, address: Long, receiverSize: Long, elementSize: Long,
        stringData: Long, stringLength: Long, argument: ArgumentScalar
    ): FirstNamedPointer {
        require(
            stringData in 0..4096 && stringLength in 0..4096 &&
                    (stringData + 8 <= stringLength || stringLength + 8 <= stringData)
        )
        val flow = SysVReceiverFlow(bytes, address, receiverSize, argument = argument)
        val code = flow.instructions
        val indices = code.mapIndexed { index, instruction -> instruction.offset to index }.toMap()
        val failures = mutableListOf<String>()
        val candidates =
            code.filter { it.offset in flow.reachable && it.operation == Operation.CMOV && it.condition == 4 }
        require(candidates.size in 1..64) { "No bounded equality-based pointer selection" }
        val matches = candidates.mapNotNull { select ->
            try {
                val destination = select.destination as? Register ?: error("Selection does not write a register")
                val source = select.source as? Register ?: error("Selection does not use a pointer register")
                require(destination.width == 8 && source.width == 8)
                val registers = flow.before(select.offset)
                val objectPointer =
                    registers[source.number] as? Pointer ?: error("Selection source is not a pointer load")
                val begin = objectPointer.base as? Pointer ?: error("Selection source is not a range element")
                require(objectPointer.offset == 0L && begin.base == Receiver() && begin.offset in 0..receiverSize - 8)
                val objectLoad = code[indices.getValue(objectPointer.loadedAt)]
                require(objectLoad.operation == Operation.MOV && objectLoad.source is Memory && objectLoad.source.width == 8)
                val selectIndex = indices.getValue(select.offset)
                val guardIndex = (selectIndex - 1 downTo 0).firstOrNull { code[it].operation == Operation.JCC }
                    ?: error("Name predicate has no length guard")
                val guard = code[guardIndex]
                require(guardIndex > 0 && selectIndex + 2 < code.size && guard.condition == 5 && selectIndex - guardIndex in 2..64)
                val failure = (guard.destination as? Immediate)?.value ?: error("Indirect name guard")
                val lengthCheck = code[guardIndex - 1]
                require(lengthCheck.operation == Operation.CMP)
                val length = lengthCheck.destination as? Memory ?: error("Name length is not a member")
                val expectedSize = (lengthCheck.source as? Immediate)?.value ?: error("Name length is not constant")
                require(
                    expectedSize in 1..64 && length.width == 8 && !length.relative && length.index == null &&
                        length.base?.let { flow.before(lengthCheck.offset)[it] } == objectPointer)
                val name = length.displacement - stringLength
                require(name >= 0 && name <= elementSize - maxOf(stringData, stringLength) - 8)
                require(flow.requiresEdge(select.offset, guard.offset, guard.offset + guard.size))
                val start = guardIndex + 1
                for (index in start..selectIndex) require(flow.predecessors[code[index].offset] == setOf(code[index - 1].offset)) {
                    "Name predicate has another entry path"
                }
                val expected = predicate(
                    code.subList(start, selectIndex + 1), flow.before(code[start].offset),
                    objectPointer, name + stringData, expectedSize.toInt()
                )

                fun localSlot(memory: Memory, at: Long): Long {
                    require(memory.width == 8 && !memory.relative && memory.index == null)
                    val values = flow.before(at)
                    val base =
                        memory.base?.let { values[it] } as? Stack ?: error("Selector result escapes its local frame")
                    val top = values[4] as? Stack ?: error("Unproven selector frame")
                    val offset = base.offset + memory.displacement
                    require(offset in top.offset..-8)
                    return offset
                }

                val absent = code[indices.getValue(failure)]
                val next = code[selectIndex + 1]
                if (next.operation == Operation.MOV) {
                    require(next.source == destination)
                    val output = next.destination as? Memory ?: error("Selected pointer is not stored locally")
                    val slot = localSlot(output, next.offset)
                    require(absent.operation == Operation.MOV && absent.source == Immediate(0))
                    val absentOutput =
                        absent.destination as? Memory ?: error("Missing selection does not clear its result")
                    require(localSlot(absentOutput, absent.offset) == slot)
                    val join = code[selectIndex + 2]
                    require(join.operation == Operation.JMP && join.destination == Immediate(absent.offset + absent.size)) {
                        "Present and absent selections do not join immediately"
                    }
                } else {
                    require(next.operation == Operation.JMP && next.destination == Immediate(absent.offset + absent.size))
                    val cleared =
                        absent.destination as? Register ?: error("Missing selection does not clear a register")
                    require(
                        absent.operation == Operation.XOR && cleared == absent.source &&
                                cleared.number == destination.number && cleared.width in setOf(4, 8)
                    )
                    val store = code[indices.getValue(absent.offset + absent.size)]
                    require(store.operation == Operation.MOV && store.source == destination)
                    localSlot(
                        store.destination as? Memory ?: error("Selected register is not stored locally"),
                        store.offset
                    )
                }
                val rangeGuards = code.withIndex().filter { (_, instruction) ->
                    instruction.operation == Operation.JCC && instruction.condition == 4 &&
                            instruction.destination == Immediate(failure) && instruction.offset in flow.reachable
                }.mapNotNull range@{ (index, branch) ->
                    if (index == 0) return@range null
                    val compare = code[index - 1]
                    if (compare.operation != Operation.CMP) return@range null
                    val state = flow.before(compare.offset)
                    fun pointer(operand: Operand?): Pointer? = when (operand) {
                        is Register -> if (operand.width == 8) state[operand.number] as? Pointer else null
                        is Memory -> if (operand.width == 8 && !operand.relative && operand.index == null &&
                            operand.base?.let { state[it] } == Receiver() && operand.displacement in 0..receiverSize - 8
                        )
                            Pointer(Receiver(), operand.displacement, compare.offset) else null

                        else -> null
                    }

                    val first = pointer(compare.destination)
                    val second = pointer(compare.source)
                    val end = when (begin) {
                        first -> second
                        second -> first
                        else -> null
                    } ?: return@range null
                    if (end.base != Receiver() || end.offset == begin.offset || end.offset !in 0..receiverSize - 8 ||
                        !flow.requiresEdge(objectPointer.loadedAt, branch.offset, branch.offset + branch.size)
                    ) return@range null
                    end.offset
                }
                val end = rangeGuards.singleOrNull() ?: error("Selection lacks a unique dominating empty-range guard")
                FirstNamedPointer(begin.offset, end, name, expected)
            } catch (error: IllegalArgumentException) {
                failures += error.message.orEmpty()
                null
            } catch (error: IllegalStateException) {
                failures += error.message.orEmpty()
                null
            }
        }
        return matches.singleOrNull()
            ?: error("Missing or ambiguous first named pointer selection: ${failures.joinToString()}")
    }

    private fun predicate(
        code: List<Instruction>, initial: List<SysVReceiverFlow.Value>, objectPointer: Pointer,
        dataOffset: Long, length: Int
    ): List<Byte> {
        val registers = initial.map { if (it == objectPointer) Object else Unknown }.toMutableList<Value>()
        var flags: Value = Unknown
        fun read(operand: Operand?): Value = when (operand) {
            is Immediate -> Constant(operand.value)
            is Register -> registers[operand.number].also {
                require(operand.width in setOf(4, 8) || it is Bytes && it.width <= operand.width)
            }

            is Memory -> {
                require(!operand.relative && operand.index == null)
                when (operand.base?.let { registers[it] }) {
                    Object -> {
                        require(operand.width == 8 && operand.displacement == dataOffset)
                        Data
                    }

                    Data -> {
                        require(
                            operand.width in setOf(
                                1,
                                2,
                                4,
                                8
                            ) && operand.displacement in 0..length.toLong() - operand.width
                        )
                        Bytes(operand.displacement.toInt(), operand.width)
                    }

                    else -> error("Name predicate reads an unrelated value")
                }
            }

            else -> Unknown
        }

        fun write(target: Operand?, value: Value) {
            val register = target as? Register ?: error("Name predicate mutates memory")
            require(register.width in setOf(4, 8))
            require(register.width == 8 || value != Object && value != Data)
            registers[register.number] = if (register.width == 4 && value is Constant)
                Constant(value.value and 0xffffffffL) else value
        }

        fun difference(left: Value, right: Value): Mismatch {
            val bytes = (left as? Bytes) ?: (right as? Bytes) ?: error("Name XOR does not compare bytes")
            val constant = (left as? Constant) ?: (right as? Constant) ?: error("Name XOR has no literal")
            require(bytes.width == 8 || constant.value >= 0 && constant.value < (1L shl (bytes.width * 8)))
            return Mismatch((0 until bytes.width).associate { bytes.offset + it to (constant.value ushr (it * 8)).toByte() })
        }
        for (instruction in code) when (instruction.operation) {
            Operation.NOP -> Unit
            Operation.MOV, Operation.MOVZX -> write(instruction.destination, read(instruction.source))
            Operation.XOR -> {
                val value = if (instruction.destination == instruction.source && instruction.destination is Register)
                    Constant(0) else difference(read(instruction.destination), read(instruction.source))
                write(instruction.destination, value)
                flags = value
            }

            Operation.OR -> {
                val left = read(instruction.destination) as? Mismatch ?: error("Name OR lacks byte comparison")
                val right = read(instruction.source) as? Mismatch ?: error("Name OR lacks byte comparison")
                require(left.bytes.keys.intersect(right.bytes.keys).isEmpty()) { "Name comparisons overlap" }
                flags = Mismatch(left.bytes + right.bytes)
                write(instruction.destination, flags)
            }

            Operation.CMOV -> {
                require(
                    instruction == code.last() && instruction.condition == 4 &&
                            read(instruction.destination) == Constant(0) && read(instruction.source) == Object
                )
                val mismatch = flags as? Mismatch ?: error("Name selection has no byte equality condition")
                require(mismatch.bytes.keys == (0 until length).toSet()) { "Name comparison leaves unchecked bytes" }
                return (0 until length).map { mismatch.bytes.getValue(it) }
            }

            else -> error("Unsupported name predicate instruction: ${instruction.operation}")
        }
        error("Name predicate has no terminal selection")
    }
}
