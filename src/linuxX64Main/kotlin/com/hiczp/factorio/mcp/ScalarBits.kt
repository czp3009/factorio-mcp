package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Bounded bit provenance for packed scalar fields, without evaluating unrelated payload bits. */
internal class ScalarBits(private val flow: X64ControlFlow, private val extents: Map<Int, Long>) {
    sealed interface Bit

    data class Constant(val value: Boolean) : Bit

    data class Input(val field: SysVArgumentFlow.Read, val bit: Int) : Bit

    private val arguments = SysVArgumentFlow(flow)
    private val scalars = ScalarExpression(flow, extents, arguments)

    private data class Key(val site: Long, val register: Int, val bit: Int)

    private val cache = mutableMapOf<Key, Bit>()
    private val active = mutableSetOf<Key>()
    private var work = 0

    fun operand(site: Long, operand: Operand?, bit: Int): Bit {
        require(bit in 0..63)
        return when (operand) {
            is Immediate -> Constant(operand.value ushr bit and 1L != 0L)
            is Register -> {
                require(operand.number in 0..15 && operand.width in setOf(1, 2, 4, 8))
                if (bit >= operand.width * 8) Constant(false) else before(site, operand.number, bit)
            }
            is Memory -> {
                require(operand.width in setOf(1, 2, 4, 8))
                if (bit >= operand.width * 8) Constant(false)
                else {
                    val read = checkNotNull(arguments.memory(site, operand))
                    val extent = checkNotNull(extents[read.reference.argument])
                    require(read.reference.offset in 0..extent - read.width)
                    Input(read, bit)
                }
            }
            else -> error("Unsupported packed scalar operand")
        }
    }

    private fun before(site: Long, register: Int, bit: Int): Bit {
        val key = Key(site, register, bit)
        cache[key]?.let {
            return it
        }
        require(++work <= 8192 && active.size < 128 && active.add(key)) {
            "Packed scalar slice is cyclic or exceeds bounds"
        }
        try {
            val values =
                scalars
                    .definitions(site, register)
                    .map { instruction ->
                        val target =
                            instruction.destination as? Register
                                ?: error("Packed value loses register provenance")
                        require(target.number == register && target.width in setOf(1, 2, 4, 8))
                        if (target.width == 4 && bit >= 32) Constant(false)
                        else if (bit >= target.width * 8) before(instruction.offset, register, bit)
                        else
                            when (instruction.operation) {
                                Operation.MOV,
                                Operation.MOVZX ->
                                    operand(instruction.offset, instruction.source, bit)
                                Operation.XOR -> {
                                    require(instruction.source == target)
                                    Constant(false)
                                }
                                Operation.CMOV -> {
                                    val left = before(instruction.offset, register, bit)
                                    val right = operand(instruction.offset, instruction.source, bit)
                                    require(left == right) {
                                        "Conditional packed values disagree on the selected bit"
                                    }
                                    left
                                }
                                Operation.ADD -> {
                                    // A sum of two values aligned to 2^(bit+1) cannot introduce a
                                    // carry into these low bits. Other addition bits stay unknown.
                                    for (lower in 0..bit) {
                                        require(
                                            before(instruction.offset, register, lower) ==
                                                Constant(false) &&
                                                operand(
                                                    instruction.offset,
                                                    instruction.source,
                                                    lower,
                                                ) == Constant(false)
                                        ) {
                                            "Packed addition has an unproven carry"
                                        }
                                    }
                                    Constant(false)
                                }
                                Operation.SHL,
                                Operation.SHR -> {
                                    val shift =
                                        (instruction.source as? Immediate)?.value
                                            ?: error("Variable packed shift")
                                    val count = shift.toInt() and if (target.width == 8) 63 else 31
                                    val sourceBit =
                                        if (instruction.operation == Operation.SHL) bit - count
                                        else bit + count
                                    if (sourceBit !in 0 until target.width * 8) Constant(false)
                                    else before(instruction.offset, register, sourceBit)
                                }
                                Operation.AND,
                                Operation.OR -> {
                                    val right = runCatching {
                                        operand(instruction.offset, instruction.source, bit)
                                    }
                                    val decisive = Constant(instruction.operation == Operation.OR)
                                    if (right.getOrNull() == decisive) decisive
                                    else {
                                        val left = before(instruction.offset, register, bit)
                                        when {
                                            left == decisive -> left
                                            left == right.getOrThrow() -> left
                                            right.getOrThrow() is Constant -> left
                                            left is Constant -> right.getOrThrow()
                                            else ->
                                                error(
                                                    "Packed operation combines different input bits"
                                                )
                                        }
                                    }
                                }
                                else ->
                                    error(
                                        "Unsupported packed scalar definition: ${instruction.operation}"
                                    )
                            }
                    }
                    .toSet()
            return (values.singleOrNull() ?: error("Packed value has ambiguous reaching bits"))
                .also { cache[key] = it }
        } finally {
            active.remove(key)
        }
    }
}
