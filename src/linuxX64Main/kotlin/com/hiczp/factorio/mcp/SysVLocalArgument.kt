package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/**
 * Register-held frame addresses at normal call sites. Never recovers a pointer from saved local contents,
 * proves a foreign memory access, or authorizes frame restoration/unwinding. Callees may borrow local storage.
 */
internal class SysVLocalArgument(private val flow: X64ControlFlow) {
    private val before = mutableMapOf<Long, List<Long?>>()

    init {
        require(flow.instructions.none {
            it.operation in listOf(
                Operation.MULTIPLY_WIDE,
                Operation.BYTE_COMPARE_EXCHANGE
            )
        }) {
            "Local argument analysis does not model this instruction's implicit register writes"
        }
        val initial = MutableList<Long?>(32) { null }
        initial[4] = 0
        before[0] = initial
        val pending = ArrayDeque<Long>()
        pending.add(0)
        var steps = 0
        while (pending.isNotEmpty()) {
            require(++steps <= 262144) { "Local argument analysis exceeds bound" }
            val position = pending.removeFirst()
            val registers = before.getValue(position).toMutableList()
            val instruction = flow.body.getValue(position)
            fun write(target: X64Instructions.Operand?, value: Long?) {
                if (target !is Register) return
                val result = if (target.width == 8) value else null
                if (target.number == 4) require(result != null && result in -16384..0) {
                    "Local argument frame has an unproven stack pointer"
                }
                registers[target.number] = result
            }

            fun read(operand: X64Instructions.Operand?): Long? =
                (operand as? Register)?.takeIf { it.width == 8 }?.let { registers[it.number] }
            when (instruction.operation) {
                Operation.PUSH -> registers[4] = checkNotNull(registers[4]) - 8
                Operation.POP -> {
                    require(instruction.destination is Register && instruction.destination.number != 4)
                    write(instruction.destination, null)
                    registers[4] = checkNotNull(registers[4]) + 8
                }

                Operation.MOV -> write(instruction.destination, read(instruction.source))
                Operation.LEA -> {
                    val source = instruction.source as? Memory ?: error("Invalid address operand")
                    val base = source.base?.let { registers[it] }
                    write(
                        instruction.destination, if (!source.relative && source.index == null && base != null &&
                            source.displacement in -16384..16384
                        ) base + source.displacement else null
                    )
                }

                Operation.ADD, Operation.SUB -> {
                    val base = read(instruction.destination)
                    val value = (instruction.source as? Immediate)?.value
                    write(
                        instruction.destination, if (base != null && value != null && value in -16384..16384)
                            base + if (instruction.operation == Operation.ADD) value else -value else null
                    )
                }

                Operation.XCHG -> {
                    val left = read(instruction.destination)
                    val right = read(instruction.source)
                    write(instruction.destination, right)
                    write(instruction.source, left)
                }

                Operation.CMOV -> {
                    val left = read(instruction.destination)
                    write(instruction.destination, left.takeIf { it == read(instruction.source) })
                }

                Operation.CALL -> {
                    require((checkNotNull(registers[4]) + 8) % 16 == 0L) { "Local argument call frame is unaligned" }
                    for (register in listOf(0, 1, 2, 6, 7, 8, 9, 10, 11) + (16..31)) registers[register] = null
                }

                Operation.CMP, Operation.TEST, Operation.BIT_TEST, Operation.SCALAR_COMPARE, Operation.NOP, Operation.ENDBR,
                Operation.JMP, Operation.JCC, Operation.RET -> Unit

                else -> write(instruction.destination, null)
            }
            require(checkNotNull(registers[4]) in -16384..0) { "Local frame exceeds analysis bound" }
            for (next in flow.successors.getValue(position)) {
                val old = before[next]
                val merged = if (old == null) registers.toList() else {
                    require(old[4] == registers[4]) { "Local argument branches disagree on stack depth" }
                    old.mapIndexed { index, value -> value.takeIf { it == registers[index] } }
                }
                if (merged != old) {
                    before[next] = merged
                    pending.add(next)
                }
            }
        }
    }

    fun registers(offset: Long): List<Long?> = before[offset] ?: error("Local argument has no normal entry path")

    fun address(offset: Long, memory: Memory): Long? {
        val base = memory.base?.let { registers(offset)[it] } ?: return null
        require(!memory.relative && memory.index == null && memory.displacement in -16384..16384) {
            "Local argument uses an indexed or unbounded frame address"
        }
        return base + memory.displacement
    }

    fun argument(call: Long, register: Int, extent: Int): Long {
        require(
            flow.body[call]?.operation == Operation.CALL && register in listOf(
                7,
                6,
                2,
                1,
                8,
                9
            ) && extent in 1..4096
        )
        val state = registers(call)
        val stack = checkNotNull(state[4])
        val pointer = state[register] ?: error("Call argument is not a register-held local address")
        require((stack + 8) % 16 == 0L && pointer >= stack && pointer <= -extent) {
            "Call argument exceeds its local frame"
        }
        return pointer
    }

    /** Storage at the call-time stack pointer. The callee's by-value classification is checked separately. */
    fun outgoing(call: Long, extent: Int): Long {
        require(flow.body[call]?.operation == Operation.CALL && extent in 1..4096)
        val stack = checkNotNull(registers(call)[4])
        require((stack + 8) % 16 == 0L && stack <= -extent) { "Outgoing argument exceeds the reserved frame" }
        return stack
    }
}
