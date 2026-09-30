package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Original pointer/entry-stack addresses held in registers. Does not recover saved pointers or authorize reads. */
internal class SysVArgumentFlow(
    private val flow: X64ControlFlow, includeStack: Boolean = false,
    entryReferences: Map<Int, Reference>? = null, allowByteCompareExchange: Boolean = false
) {
    data class Reference(val argument: Int, val offset: Long = 0)
    data class Read(val reference: Reference, val width: Int)

    private val before = mutableMapOf<Long, List<Reference?>>()

    init {
        require(flow.instructions.none {
            it.operation in listOf(
                Operation.MULTIPLY_WIDE,
                Operation.ATOMIC_EXCHANGE_ADD
            )
        })
        require(allowByteCompareExchange || flow.instructions.none { it.operation == Operation.BYTE_COMPARE_EXCHANGE })
        val initial = MutableList<Reference?>(32) { null }
        val references = entryReferences ?: listOf(7, 6, 2, 1, 8, 9).associateWith { Reference(it) }
        // A caller specializing a suffix must independently prove its entry references.
        require(references.all { (register, reference) ->
            register in 0..15 && register !in listOf(4, 5) &&
                    reference.argument in listOf(7, 6, 2, 1, 8, 9) && reference.offset in -4096..4096
        })
        for ((register, reference) in references) initial[register] = reference
        if (includeStack) initial[4] = Reference(4)
        before[0] = initial
        val pending = ArrayDeque<Long>()
        pending.add(0)
        var steps = 0
        while (pending.isNotEmpty()) {
            require(++steps <= 262144) { "Original argument analysis exceeds bound" }
            val offset = pending.removeFirst()
            val registers = before.getValue(offset).toMutableList()
            val instruction = flow.body.getValue(offset)
            fun value(operand: X64Instructions.Operand?): Reference? =
                (operand as? Register)?.takeIf { it.width == 8 }?.let { registers[it.number] }

            fun write(operand: X64Instructions.Operand?, reference: Reference?) {
                if (operand is Register) registers[operand.number] = reference.takeIf { operand.width == 8 }
            }

            fun adjusted(reference: Reference?, displacement: Long): Reference? {
                if (reference == null || displacement !in -4096..4096) return null
                val offset = reference.offset + displacement
                return reference.copy(offset = offset).takeIf { offset in -4096..4096 }
            }
            when (instruction.operation) {
                Operation.MOV, Operation.SCALAR_MOV -> write(instruction.destination, value(instruction.source))
                Operation.LEA -> {
                    val source = instruction.source as? Memory ?: error("Invalid argument address")
                    write(
                        instruction.destination, if (!source.relative && source.index == null)
                        adjusted(source.base?.let { registers[it] }, source.displacement) else null
                    )
                }

                Operation.ADD, Operation.SUB -> {
                    val amount = (instruction.source as? Immediate)?.value
                    write(
                        instruction.destination, if (amount != null && amount in -4096..4096)
                            adjusted(
                                value(instruction.destination),
                                if (instruction.operation == Operation.ADD) amount else -amount
                            )
                        else null
                    )
                }

                Operation.CMOV -> {
                    val left = value(instruction.destination)
                    write(instruction.destination, left.takeIf { it == value(instruction.source) })
                }

                Operation.XCHG -> {
                    val left = value(instruction.destination)
                    val right = value(instruction.source)
                    write(instruction.destination, right)
                    write(instruction.source, left)
                }

                Operation.CALL -> for (register in listOf(0, 1, 2, 6, 7, 8, 9, 10, 11) + (16..31)) registers[register] =
                    null

                Operation.BYTE_COMPARE_EXCHANGE -> {
                    require(
                        instruction.destination is Memory && instruction.destination.width == 1 &&
                                instruction.source is Register && instruction.source.width == 1
                    )
                    // AL may be replaced by the memory value. Its unchanged upper bits do not preserve a pointer.
                    registers[0] = null
                }

                Operation.PUSH, Operation.POP -> {
                    val operand = instruction.destination
                    if (includeStack) require(
                        operand is Register && operand.width == 8 ||
                                operand is Memory && operand.width == 8 || operand is Immediate
                    ) {
                        "Unsupported argument-analysis stack operation width"
                    }
                    registers[4] = adjusted(registers[4], if (instruction.operation == Operation.PUSH) -8 else 8)
                    if (instruction.operation == Operation.POP) write(operand, null)
                }

                Operation.CMP, Operation.TEST, Operation.BIT_TEST, Operation.SCALAR_COMPARE, Operation.NOP, Operation.ENDBR,
                Operation.JMP, Operation.JCC, Operation.RET -> Unit

                else -> write(instruction.destination, null)
            }
            for (next in flow.successors.getValue(offset)) {
                val old = before[next]
                val merged = if (old == null) registers.toList() else old.mapIndexed { index, reference ->
                    reference.takeIf { it == registers[index] }
                }
                if (old != merged) {
                    before[next] = merged
                    pending.add(next)
                }
            }
        }
    }

    fun source(offset: Long): Read? {
        val operand = flow.body.getValue(offset).source as? Memory ?: return null
        return memory(offset, operand)
    }

    fun register(offset: Long, number: Int): Reference? {
        require(number in 0..31)
        return before[offset]?.get(number)
    }

    fun memory(offset: Long, memory: Memory): Read? {
        if (memory.relative || memory.index != null || memory.displacement !in -4096..4096) return null
        val reference = memory.base?.let { before[offset]?.get(it) } ?: return null
        return Read(reference.copy(offset = reference.offset + memory.displacement), memory.width)
    }
}
