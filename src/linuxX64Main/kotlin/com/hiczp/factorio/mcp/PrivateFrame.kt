package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Proves that local storage never escapes. Joins retain every possible frame alias. */
internal object PrivateFrame {
    fun verify(flow: X64ControlFlow) {
        val frame = SysVLocalArgument(flow)
        val before = mutableMapOf(0L to setOf(4))
        val pending = ArrayDeque<Long>()
        pending.add(0)
        var work = 0
        while (pending.isNotEmpty()) {
            require(++work <= 262144) { "Private frame analysis exceeds bound" }
            val site = pending.removeFirst()
            val aliases = before.getValue(site).toMutableSet()
            val instruction = flow.body.getValue(site)
            val memory = instruction.destination as? Memory
            if (
                memory != null &&
                    instruction.operation !in
                        listOf(
                            Operation.CMP,
                            Operation.TEST,
                            Operation.BIT_TEST,
                            Operation.SCALAR_COMPARE,
                        ) &&
                    (memory.base in aliases || memory.index in aliases)
            ) {
                require(frame.address(site, memory) != null) {
                    "Write uses an ambiguous private frame alias"
                }
            }
            fun reads(operand: Operand?): Boolean = operand is Register && operand.number in aliases
            fun write(target: Operand?, alias: Boolean) {
                when (target) {
                    is Register -> {
                        require(!alias || target.width == 8)
                        require(target.width in listOf(4, 8, 16) || target.number !in aliases) {
                            "Partial write loses a private frame alias"
                        }
                        if (alias) aliases.add(target.number) else aliases.remove(target.number)
                    }
                    is Memory ->
                        require(!alias) {
                            "Private frame alias is stored outside register tracking"
                        }
                    else -> error("Unsupported private frame destination")
                }
            }
            when (instruction.operation) {
                Operation.MOV,
                Operation.MOVZX,
                Operation.MOVSX,
                Operation.SCALAR_MOV,
                Operation.VECTOR_MOV -> write(instruction.destination, reads(instruction.source))
                Operation.LEA -> {
                    val memory = instruction.source as? Memory ?: error("Missing address operand")
                    val alias = memory.base in aliases || memory.index in aliases
                    require(
                        !alias ||
                            !memory.relative &&
                                memory.index == null &&
                                memory.displacement in -16384..16384
                    )
                    write(instruction.destination, alias)
                }
                Operation.CMOV ->
                    write(
                        instruction.destination,
                        reads(instruction.destination) || reads(instruction.source),
                    )
                Operation.XCHG -> {
                    val left = reads(instruction.destination)
                    val right = reads(instruction.source)
                    write(instruction.destination, right)
                    write(instruction.source, left)
                }
                Operation.PUSH ->
                    require(!reads(instruction.destination)) {
                        "Private frame alias is pushed outside register tracking"
                    }
                Operation.POP -> write(instruction.destination, false)
                Operation.CALL -> {
                    val volatile = listOf(0, 1, 2, 6, 7, 8, 9, 10, 11) + (16..31)
                    // Incidental values in return/scratch registers are not System V arguments.
                    // This analysis observes existing game calls; it does not create an adapter
                    // ABI.
                    val inputs = listOf(7, 6, 2, 1, 8, 9) + (16..23)
                    require(inputs.none { it in aliases }) {
                        "Native call may expose private frame storage"
                    }
                    aliases.removeAll(volatile.toSet())
                }
                Operation.ADD,
                Operation.SUB -> {
                    if (reads(instruction.destination) || reads(instruction.source)) {
                        require(
                            instruction.source is Immediate &&
                                instruction.source.value in -16384..16384
                        )
                        write(instruction.destination, true)
                    } else write(instruction.destination, false)
                }
                Operation.XOR -> {
                    if (
                        instruction.destination == instruction.source &&
                            instruction.destination is Register &&
                            instruction.destination.width in listOf(4, 8)
                    )
                        write(instruction.destination, false)
                    else {
                        require(!reads(instruction.destination) && !reads(instruction.source))
                        write(instruction.destination, false)
                    }
                }
                Operation.ATOMIC_EXCHANGE_ADD -> {
                    require(!reads(instruction.source))
                    write(instruction.source, false)
                }
                Operation.CMP,
                Operation.TEST,
                Operation.BIT_TEST,
                Operation.SCALAR_COMPARE,
                Operation.NOP,
                Operation.ENDBR,
                Operation.JCC,
                Operation.JMP,
                Operation.RET,
                Operation.TRAP -> Unit
                else -> {
                    require(!reads(instruction.destination) && !reads(instruction.source)) {
                        "Unsupported operation transforms a private frame alias"
                    }
                    write(instruction.destination, false)
                }
            }
            for (next in flow.successors.getValue(site)) {
                val old = before[next]
                val merged = old.orEmpty() + aliases
                if (old != merged) {
                    before[next] = merged
                    pending.add(next)
                }
            }
        }
    }
}
