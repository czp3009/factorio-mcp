package com.hiczp.factorio.mcp

/** A deliberately incomplete decoder. Unknown encodings fail before they can authorize a read or patch. */
internal class X64Instructions(
    private val bytes: BinaryView, private val allowWideMultiply: Boolean = false,
    private val allowByteCompareExchange: Boolean = false,
    private val allowAtomicExchangeAdd: Boolean = false,
    private val allowUnsignedWideMultiply: Boolean = false
) {
    sealed interface Operand
    data class Register(val number: Int, val width: Int) : Operand
    data class Memory(
        val base: Int?,
        val index: Int?,
        val scale: Int,
        val displacement: Long,
        val width: Int,
        val relative: Boolean = false,
    ) : Operand

    data class Immediate(val value: Long) : Operand

    enum class Operation {
        MOV, MOVZX, MOVSX, LEA, PUSH, POP, ADD, ADC, SUB, SBB, INC, DEC, NOT, NEG, MULTIPLY, MULTIPLY_IMMEDIATE, MULTIPLY_WIDE,
        AND, OR, XOR, SHR, SAR, SHL, ROL, ROR, CMP, TEST, BIT_TEST, BIT_SCAN_REVERSE,
        CALL, JMP, JCC, RET, NOP, ENDBR, XCHG, CMOV, SET, ATOMIC_INC, ATOMIC_DEC, BYTE_COMPARE_EXCHANGE, ATOMIC_EXCHANGE_ADD,
        VECTOR_MOV, VECTOR_HIGH_LOAD, VECTOR_MOVE_LOW_TO_HIGH, VECTOR_UNPACK_LOW_BYTES, VECTOR_UNPACK_LOW_QWORDS, VECTOR_PACK_UNSIGNED_BYTES, VECTOR_SHUFFLE_LOW_WORDS,
        VECTOR_XOR, VECTOR_AND, VECTOR_AND_NOT, VECTOR_OR, VECTOR_ADD_DWORDS,
        VECTOR_EQUAL_DWORDS, VECTOR_GREATER_DWORDS, VECTOR_SHIFT_LEFT_DWORDS, VECTOR_SHIFT_RIGHT_DWORDS,
        VECTOR_ADD_FLOATS, VECTOR_MULTIPLY_FLOATS, VECTOR_SHUFFLE_FLOATS, VECTOR_TRUNCATE_FLOATS, VECTOR_INTS_TO_FLOATS,
        SCALAR_MOV, DOUBLE_ADD, DOUBLE_SUBTRACT, DOUBLE_MULTIPLY, DOUBLE_DIVIDE, DOUBLE_MINIMUM, DOUBLE_MAXIMUM,
        SCALAR_COMPARE, INT_TO_DOUBLE, TRUNCATE_DOUBLE, INT_TO_FLOAT,
    }

    data class Instruction(
        val offset: Long,
        val size: Int,
        val operation: Operation,
        val destination: Operand? = null,
        val source: Operand? = null,
        val condition: Int? = null,
        val control: Int? = null,
        val immediate: Long? = null,
    )

    fun decode(offset: Long): Instruction {
        val input = bytes.cursor(offset)
        fun read(width: Int): Long {
            require(input.position - offset + width <= 15) { "x64 instruction exceeds architectural length" }
            return input.unsigned(width)
        }

        fun signed(width: Int): Long = read(width).let { it shl (64 - width * 8) shr (64 - width * 8) }
        var opcode = read(1).toInt()
        val locked = opcode == 0xf0
        if (locked) opcode = read(1).toInt()
        var operandPrefixes = 0
        var codeSegmentPrefixes = 0
        while (opcode == 0x66 || opcode == 0x2e) {
            if (opcode == 0x66) operandPrefixes++ else codeSegmentPrefixes++
            opcode = read(1).toInt()
        }
        val word = operandPrefixes > 0
        val paddingPrefixes = operandPrefixes > 1 || codeSegmentPrefixes > 0
        var rex = 0
        var hasRex = false
        if (opcode in 0x40..0x4f) {
            hasRex = true
            rex = opcode and 15
            opcode = read(1).toInt()
        }
        require(!paddingPrefixes || opcode == 0x0f) { "Repeated/segment prefixes are supported only on multibyte NOPs" }
        val width = if (rex and 8 != 0) 8 else if (word) 2 else 4
        fun reg(number: Int, size: Int): Register {
            require(size != 1 || hasRex || number !in 4..7) { "High-byte registers are unsupported" }
            return Register(number, size)
        }

        data class ModRm(val group: Int, val register: Register, val operand: Operand)

        fun modRm(size: Int, registerSize: Int = size): ModRm {
            val encoded = read(1).toInt()
            val mode = encoded ushr 6
            val group = (encoded ushr 3) and 7
            val register = reg(group + if (rex and 4 != 0) 8 else 0, registerSize)
            val rm = encoded and 7
            if (mode == 3) return ModRm(group, register, reg(rm + if (rex and 1 != 0) 8 else 0, size))
            var base: Int? = rm + if (rex and 1 != 0) 8 else 0
            var index: Int? = null
            var scale = 1
            var relative = false
            var displacement = 0L
            if (rm == 4) {
                val sib = read(1).toInt()
                scale = 1 shl (sib ushr 6)
                val indexBits = (sib ushr 3) and 7
                if (indexBits != 4 || rex and 2 != 0) index = indexBits + if (rex and 2 != 0) 8 else 0
                val baseBits = sib and 7
                base = baseBits + if (rex and 1 != 0) 8 else 0
                if (mode == 0 && baseBits == 5) {
                    base = null
                    displacement = signed(4)
                }
            } else if (mode == 0 && rm == 5) {
                base = null
                relative = true
                displacement = signed(4)
            }
            if (mode == 1) displacement = signed(1)
            if (mode == 2) displacement = signed(4)
            return ModRm(group, register, Memory(base, index, scale, displacement, size, relative))
        }

        fun result(
            op: Operation, dst: Operand? = null, src: Operand? = null, condition: Int? = null,
            control: Int? = null, immediate: Long? = null
        ) =
            Instruction(offset, (input.position - offset).toInt(), op, dst, src, condition, control, immediate)

        fun branch(op: Operation, size: Int, condition: Int? = null): Instruction {
            require(!word && !hasRex) { "Prefixed branches are unsupported" }
            val displacement = signed(size)
            val end = input.position
            require(displacement <= Long.MAX_VALUE - end) { "x64 branch target overflows" }
            return result(op, Immediate(end + displacement), condition = condition)
        }
        if (locked) {
            if (opcode == 0x0f) {
                val second = read(1)
                if (second == 0xc1L) {
                    require(allowAtomicExchangeAdd && !word && codeSegmentPrefixes == 0) {
                        "Atomic exchange-add requires an explicit register-effects model"
                    }
                    val operands = modRm(width)
                    require(operands.operand is Memory) { "Locked exchange-add requires memory" }
                    return result(Operation.ATOMIC_EXCHANGE_ADD, operands.operand, operands.register)
                }
                require(allowByteCompareExchange && !word && codeSegmentPrefixes == 0 && rex and 8 == 0 && second == 0xb0L) {
                    "Byte compare-exchange requires an explicit accumulator-effects model"
                }
                val operands = modRm(1)
                require(operands.operand is Memory) { "Locked byte compare-exchange requires memory" }
                return result(Operation.BYTE_COMPARE_EXCHANGE, operands.operand, operands.register)
            }
            require(opcode == 0xff && !word && codeSegmentPrefixes == 0 && rex and 4 == 0) {
                "Unsupported locked instruction"
            }
            val operands = modRm(width)
            require(operands.group in 0..1 && operands.operand is Memory) { "Unsupported locked increment/decrement operand" }
            return result(if (operands.group == 0) Operation.ATOMIC_INC else Operation.ATOMIC_DEC, operands.operand)
        }
        return when (opcode) {
            0xf2 -> {
                require(!word && !hasRex) { "Unsupported scalar prefix" }
                var escape = read(1).toInt()
                if (escape in 0x40..0x4f) {
                    rex = escape and 15
                    hasRex = true
                    escape = read(1).toInt()
                }
                require(escape == 0x0f) { "Unsupported scalar escape" }
                val second = read(1).toInt()
                if (second == 0x70) {
                    require(rex and 8 == 0) { "Unsupported packed word shuffle prefix" }
                    val operands = modRm(16)
                    val source = when (val value = operands.operand) {
                        is Register -> value.copy(number = value.number + 16)
                        else -> value
                    }
                    return result(
                        Operation.VECTOR_SHUFFLE_LOW_WORDS,
                        operands.register.copy(number = operands.register.number + 16),
                        source,
                        control = read(1).toInt()
                    )
                }
                if (second == 0x2a) {
                    val operands = modRm(if (rex and 8 != 0) 8 else 4, 8)
                    return result(
                        Operation.INT_TO_DOUBLE, operands.register.copy(number = operands.register.number + 16),
                        operands.operand
                    )
                }
                if (second == 0x2c) {
                    val operands = modRm(8, if (rex and 8 != 0) 8 else 4)
                    val source = when (val value = operands.operand) {
                        is Register -> value.copy(number = value.number + 16)
                        else -> value
                    }
                    return result(Operation.TRUNCATE_DOUBLE, operands.register, source)
                }
                require(
                    second in listOf(
                        0x10,
                        0x11,
                        0x58,
                        0x59,
                        0x5c,
                        0x5d,
                        0x5e,
                        0x5f
                    ) && rex and 8 == 0
                ) { "Unsupported scalar operation" }
                val operands = modRm(8)
                val register = operands.register.copy(number = operands.register.number + 16)
                val operand = when (val value = operands.operand) {
                    is Register -> value.copy(number = value.number + 16)
                    else -> value
                }
                if (second == 0x11) result(Operation.SCALAR_MOV, operand, register)
                else if (second == 0x58) result(Operation.DOUBLE_ADD, register, operand)
                else if (second == 0x59) result(Operation.DOUBLE_MULTIPLY, register, operand)
                else if (second == 0x5c) result(Operation.DOUBLE_SUBTRACT, register, operand)
                else if (second == 0x5d) result(Operation.DOUBLE_MINIMUM, register, operand)
                else if (second == 0x5f) result(Operation.DOUBLE_MAXIMUM, register, operand)
                else if (second == 0x5e) result(Operation.DOUBLE_DIVIDE, register, operand)
                else result(Operation.SCALAR_MOV, register, operand)
            }

            0xf3 -> {
                require(!word && !hasRex) { "Unsupported x64 repeat prefix" }
                var escape = read(1).toInt()
                if (escape in 0x40..0x4f) {
                    rex = escape and 15
                    hasRex = true
                    escape = read(1).toInt()
                }
                require(escape == 0x0f) { "Unsupported x64 repeat escape" }
                val repeatedOpcode = read(1).toInt()
                when (repeatedOpcode) {
                    0x10, 0x11 -> {
                        require(rex and 8 == 0) { "Unsupported MOVSS REX.W prefix" }
                        val operands = modRm(4)
                        val register = operands.register.copy(number = operands.register.number + 16)
                        val operand = when (val value = operands.operand) {
                            is Register -> value.copy(number = value.number + 16)
                            else -> value
                        }
                        if (repeatedOpcode == 0x11) result(Operation.SCALAR_MOV, operand, register)
                        else result(Operation.SCALAR_MOV, register, operand)
                    }

                    0x2a -> {
                        val operands = modRm(if (rex and 8 != 0) 8 else 4, 4)
                        result(
                            Operation.INT_TO_FLOAT, operands.register.copy(number = operands.register.number + 16),
                            operands.operand
                        )
                    }

                    0x5b -> {
                        require(rex and 8 == 0) { "Unsupported packed float conversion prefix" }
                        val operands = modRm(16)
                        val source = when (val value = operands.operand) {
                            is Register -> value.copy(number = value.number + 16)
                            else -> value
                        }
                        result(
                            Operation.VECTOR_TRUNCATE_FLOATS,
                            operands.register.copy(number = operands.register.number + 16), source
                        )
                    }

                    0x1e -> {
                        require(!hasRex && read(1) == 0xfaL) { "Unsupported ENDBR encoding" }
                        result(Operation.ENDBR)
                    }

                    0x7e -> {
                        require(rex and 8 == 0) { "Unsupported MOVQ REX.W prefix" }
                        val operands = modRm(8)
                        val source = when (val value = operands.operand) {
                            is Register -> value.copy(number = value.number + 16)
                            else -> value
                        }
                        result(
                            Operation.VECTOR_MOV,
                            operands.register.copy(number = operands.register.number + 16),
                            source
                        )
                    }

                    else -> error("Unsupported x64 repeat operation")
                }
            }

            0x90 -> {
                require(!hasRex) { "Unsupported accumulator exchange" }
                result(Operation.NOP)
            }

            0xc3 -> {
                require(!word && !hasRex) { "Prefixed returns are unsupported" }
                result(Operation.RET)
            }

            in 0x50..0x5f -> {
                require(!word && rex and 6 == 0) { "Unsupported stack instruction prefix" }
                result(
                    if (opcode < 0x58) Operation.PUSH else Operation.POP,
                    reg((opcode and 7) + if (rex and 1 != 0) 8 else 0, 8)
                )
            }

            0x68, 0x6a -> {
                require(!word && !hasRex) { "Unsupported immediate push prefix" }
                result(Operation.PUSH, Immediate(signed(if (opcode == 0x6a) 1 else 4)))
            }

            0x88, 0x89, 0x8a, 0x8b, 0x8d -> {
                val size = if (opcode == 0x88 || opcode == 0x8a) 1 else width
                val operands = modRm(size)
                if (opcode == 0x8d) require(operands.operand is Memory) { "LEA requires a memory operand" }
                if (opcode == 0x88 || opcode == 0x89) result(Operation.MOV, operands.operand, operands.register)
                else result(if (opcode == 0x8d) Operation.LEA else Operation.MOV, operands.register, operands.operand)
            }

            0x86, 0x87 -> {
                val operands = modRm(if (opcode == 0x86) 1 else width)
                result(Operation.XCHG, operands.operand, operands.register)
            }

            0x63 -> {
                require(!word && rex and 8 != 0) { "Only 32-to-64-bit MOVSXD is supported" }
                val operands = modRm(4, 8)
                result(Operation.MOVSX, operands.register, operands.operand)
            }

            0x69, 0x6b -> {
                val operands = modRm(width)
                result(
                    Operation.MULTIPLY_IMMEDIATE, operands.register, operands.operand,
                    immediate = signed(if (opcode == 0x6b) 1 else if (width == 2) 2 else 4)
                )
            }

            in 0xb0..0xb7 -> result(
                Operation.MOV,
                reg((opcode and 7) + if (rex and 1 != 0) 8 else 0, 1),
                Immediate(read(1))
            )

            in 0xb8..0xbf -> result(
                Operation.MOV,
                reg((opcode and 7) + if (rex and 1 != 0) 8 else 0, width),
                Immediate(read(width))
            )

            0xc6, 0xc7 -> {
                val size = if (opcode == 0xc6) 1 else width
                val operands = modRm(size, 4)
                require(operands.group == 0 && rex and 4 == 0) { "Unsupported MOV immediate extension" }
                result(Operation.MOV, operands.operand, Immediate(if (size == 8) signed(4) else read(size)))
            }

            0xe8 -> branch(Operation.CALL, 4)
            0xe9 -> branch(Operation.JMP, 4)
            0xeb -> branch(Operation.JMP, 1)
            in 0x70..0x7f -> branch(Operation.JCC, 1, opcode and 15)
            0x0f -> when (val second = read(1).toInt().also {
                require(!paddingPrefixes || it == 0x1f) { "Repeated/segment prefixes are supported only on multibyte NOPs" }
            }) {
                0xa3 -> {
                    val operands = modRm(width)
                    require(operands.operand is Register) { "Memory bit addressing requires a separate bounds model" }
                    result(Operation.BIT_TEST, operands.operand, operands.register)
                }

                0xaf -> {
                    val operands = modRm(width)
                    result(Operation.MULTIPLY, operands.register, operands.operand)
                }

                0xbd -> {
                    val operands = modRm(width)
                    result(Operation.BIT_SCAN_REVERSE, operands.register, operands.operand)
                }

                0x2e -> {
                    require(rex and 8 == 0) { "Unsupported scalar comparison prefix" }
                    val operands = modRm(if (word) 8 else 4)
                    val source = when (val value = operands.operand) {
                        is Register -> value.copy(number = value.number + 16)
                        else -> value
                    }
                    result(
                        Operation.SCALAR_COMPARE,
                        operands.register.copy(number = operands.register.number + 16),
                        source
                    )
                }

                0x58, 0x59, 0x5b, 0xc6 -> {
                    require(!word && rex and 8 == 0) { "Unsupported packed float prefix" }
                    val operands = modRm(16)
                    val source = when (val value = operands.operand) {
                        is Register -> value.copy(number = value.number + 16)
                        else -> value
                    }
                    result(
                        when (second) {
                            0x58 -> Operation.VECTOR_ADD_FLOATS
                            0x59 -> Operation.VECTOR_MULTIPLY_FLOATS
                            0x5b -> Operation.VECTOR_INTS_TO_FLOATS
                            else -> Operation.VECTOR_SHUFFLE_FLOATS
                        }, operands.register.copy(number = operands.register.number + 16), source,
                        control = if (second == 0xc6) read(1).toInt() else null
                    )
                }

                0x16 -> {
                    require(rex and 8 == 0) { "Unsupported high-vector load prefix" }
                    val operands = modRm(8)
                    if (operands.operand is Register) {
                        require(!word) { "Packed-double high load requires memory" }
                        result(
                            Operation.VECTOR_MOVE_LOW_TO_HIGH,
                            operands.register.copy(number = operands.register.number + 16, width = 16),
                            operands.operand.copy(number = operands.operand.number + 16, width = 16)
                        )
                    } else result(
                        Operation.VECTOR_HIGH_LOAD,
                        operands.register.copy(number = operands.register.number + 16, width = 16), operands.operand
                    )
                }

                0x13 -> {
                    require(rex and 8 == 0) { "Unsupported low-vector store prefix" }
                    val operands = modRm(8)
                    require(operands.operand is Memory) { "Low-vector store requires memory" }
                    result(
                        Operation.VECTOR_MOV,
                        operands.operand,
                        operands.register.copy(number = operands.register.number + 16)
                    )
                }

                0x6e, 0x7e -> {
                    require(word) { "Integer/vector transfer requires an SSE operand prefix" }
                    val operands = modRm(if (rex and 8 != 0) 8 else 4)
                    val vector = operands.register.copy(number = operands.register.number + 16)
                    if (second == 0x6e) result(Operation.VECTOR_MOV, vector, operands.operand)
                    else result(Operation.VECTOR_MOV, operands.operand, vector)
                }

                0x14, 0x60, 0x67, 0x6f, 0x7f, 0x66, 0x76, 0xdb, 0xdf, 0xeb, 0xef, 0xfe -> {
                    require(word && rex and 8 == 0) { "Unsupported packed integer prefix" }
                    val operands = modRm(16)
                    val register = operands.register.copy(number = operands.register.number + 16)
                    val operand = when (val value = operands.operand) {
                        is Register -> value.copy(number = value.number + 16)
                        else -> value
                    }
                    val operation = when (second) {
                        0x14 -> Operation.VECTOR_UNPACK_LOW_QWORDS
                        0x60 -> Operation.VECTOR_UNPACK_LOW_BYTES
                        0x67 -> Operation.VECTOR_PACK_UNSIGNED_BYTES
                        0x66 -> Operation.VECTOR_GREATER_DWORDS
                        0x76 -> Operation.VECTOR_EQUAL_DWORDS
                        0xdb -> Operation.VECTOR_AND
                        0xdf -> Operation.VECTOR_AND_NOT
                        0xeb -> Operation.VECTOR_OR
                        0xef -> Operation.VECTOR_XOR
                        0xfe -> Operation.VECTOR_ADD_DWORDS
                        else -> Operation.VECTOR_MOV
                    }
                    if (second == 0x7f) result(operation, operand, register) else result(operation, register, operand)
                }

                0x72 -> {
                    require(word && rex and 12 == 0) { "Unsupported packed shift prefix" }
                    val operands = modRm(16)
                    val target = operands.operand as? Register ?: error("Packed shift requires an XMM register")
                    val operation = when (operands.group) {
                        4 -> Operation.VECTOR_SHIFT_RIGHT_DWORDS
                        6 -> Operation.VECTOR_SHIFT_LEFT_DWORDS
                        else -> error("Unsupported packed shift group")
                    }
                    result(operation, target.copy(number = target.number + 16), Immediate(read(1)))
                }

                0xd6 -> {
                    require(word && rex and 8 == 0) { "Unsupported MOVQ store prefix" }
                    val operands = modRm(8)
                    val destination = when (val value = operands.operand) {
                        is Register -> value.copy(number = value.number + 16)
                        else -> value
                    }
                    result(
                        Operation.VECTOR_MOV,
                        destination,
                        operands.register.copy(number = operands.register.number + 16)
                    )
                }

                0x10, 0x11, 0x28, 0x29, 0x57 -> {
                    require(rex and 8 == 0) { "Unsupported vector REX.W prefix" }
                    val operands = modRm(16)
                    // XMM registers occupy a separate bank; integer analyzers must not treat them as GPRs.
                    val register = operands.register.copy(number = operands.register.number + 16)
                    val operand = when (val value = operands.operand) {
                        is Register -> value.copy(number = value.number + 16)
                        else -> value
                    }
                    if (second == 0x57) result(Operation.VECTOR_XOR, register, operand)
                    else if (second == 0x11 || second == 0x29) result(Operation.VECTOR_MOV, operand, register)
                    else result(Operation.VECTOR_MOV, register, operand)
                }

                in 0x40..0x4f -> {
                    val operands = modRm(width)
                    result(Operation.CMOV, operands.register, operands.operand, second and 15)
                }

                in 0x90..0x9f -> {
                    require(!word && rex and 4 == 0) { "Unsupported SETcc prefix" }
                    val operands = modRm(1, 4)
                    require(operands.group == 0) { "Unsupported SETcc extension" }
                    result(Operation.SET, operands.operand, condition = second and 15)
                }

                0xb6, 0xb7 -> {
                    val operands = modRm(if (second == 0xb6) 1 else 2, width)
                    result(Operation.MOVZX, operands.register, operands.operand)
                }

                0xbe, 0xbf -> {
                    val sourceWidth = if (second == 0xbe) 1 else 2
                    require(width > sourceWidth) { "Signed extension does not widen its source" }
                    val operands = modRm(sourceWidth, width)
                    result(Operation.MOVSX, operands.register, operands.operand)
                }

                0x1f -> {
                    val operands = modRm(width)
                    require(operands.group == 0 && rex and 4 == 0) { "Unsupported NOP extension" }
                    result(Operation.NOP)
                }

                in 0x80..0x8f -> branch(Operation.JCC, 4, second and 15)
                else -> error("Unsupported x64 two-byte opcode: ${second.toString(16)}")
            }

            0x80, 0x81, 0x83 -> {
                val size = if (opcode == 0x80) 1 else width
                val operands = modRm(size, 4)
                require(rex and 4 == 0) { "Unsupported arithmetic opcode extension" }
                val operation = when (operands.group) {
                    0 -> Operation.ADD
                    1 -> Operation.OR
                    2 -> Operation.ADC
                    3 -> Operation.SBB
                    4 -> Operation.AND
                    5 -> Operation.SUB
                    6 -> Operation.XOR
                    7 -> Operation.CMP
                    else -> error("Unsupported x64 arithmetic group")
                }
                val immediate = if (opcode == 0x83) signed(1) else if (size == 8) signed(4) else read(size)
                result(operation, operands.operand, Immediate(immediate))
            }

            0x04, 0x05, 0x0c, 0x0d, 0x24, 0x25, 0x2c, 0x2d, 0x34, 0x35, 0x3c, 0x3d -> {
                val operation = when (opcode and 0xf8) {
                    0x00 -> Operation.ADD
                    0x08 -> Operation.OR
                    0x20 -> Operation.AND
                    0x28 -> Operation.SUB
                    0x30 -> Operation.XOR
                    else -> Operation.CMP
                }
                val size = if (opcode and 1 == 0) 1 else width
                result(operation, reg(0, size), Immediate(if (size == 8) signed(4) else read(size)))
            }

            0xa8, 0xa9 -> result(
                Operation.TEST, reg(0, if (opcode == 0xa8) 1 else width),
                Immediate(if (opcode == 0xa8) read(1) else if (width == 8) signed(4) else read(width))
            )

            0xf6, 0xf7 -> {
                val size = if (opcode == 0xf6) 1 else width
                val operands = modRm(size, 4)
                require(rex and 4 == 0) { "Unsupported unary opcode extension" }
                when (operands.group) {
                    0 -> result(Operation.TEST, operands.operand, Immediate(if (size == 8) signed(4) else read(size)))
                    2 -> result(Operation.NOT, operands.operand)
                    3 -> result(Operation.NEG, operands.operand)
                    4 -> {
                        require(allowUnsignedWideMultiply && size == 8) {
                            "Wide unsigned multiply requires an explicit register-effects model"
                        }
                        // Control 0 distinguishes unsigned high-half semantics from the existing signed form.
                        result(Operation.MULTIPLY_WIDE, src = operands.operand, control = 0)
                    }

                    5 -> {
                        // This form also writes RAX and RDX. Existing analyzers do not opt in implicitly.
                        require(allowWideMultiply && size == 8) { "Wide signed multiply requires an explicit register-effects model" }
                        result(Operation.MULTIPLY_WIDE, src = operands.operand)
                    }

                    else -> error("Unsupported unary opcode extension")
                }
            }

            0xc0, 0xc1, 0xd0, 0xd1, 0xd2, 0xd3 -> {
                val size = if (opcode and 1 == 0) 1 else width
                val operands = modRm(size, 4)
                require(rex and 4 == 0) { "Unsupported shift opcode extension" }
                val operation = when (operands.group) {
                    0 -> Operation.ROL
                    1 -> Operation.ROR
                    4 -> Operation.SHL
                    5 -> Operation.SHR
                    7 -> Operation.SAR
                    else -> error("Unsupported x64 shift group")
                }
                result(
                    operation, operands.operand, if (opcode >= 0xd2) Register(1, 1)
                    else Immediate(if (opcode < 0xd0) read(1) else 1)
                )
            }

            0x00, 0x01, 0x02, 0x03, 0x08, 0x09, 0x0a, 0x0b, 0x20, 0x21, 0x22, 0x23,
            0x28, 0x29, 0x2a, 0x2b, 0x30, 0x31, 0x32, 0x33, 0x38, 0x39, 0x3a, 0x3b, 0x84, 0x85 -> {
                val operands = modRm(if (opcode and 1 == 0) 1 else width)
                val operation = when (opcode and 0xf8) {
                    0x00 -> Operation.ADD
                    0x08 -> Operation.OR
                    0x20 -> Operation.AND
                    0x28 -> Operation.SUB
                    0x30 -> Operation.XOR
                    0x38 -> Operation.CMP
                    else -> Operation.TEST
                }
                if (opcode and 2 != 0) result(operation, operands.register, operands.operand)
                else result(operation, operands.operand, operands.register)
            }

            0xfe -> {
                require(!word && rex and 4 == 0) { "Unsupported byte arithmetic prefix" }
                val operands = modRm(1)
                require(operands.group in 0..1) { "Unsupported byte arithmetic extension" }
                result(if (operands.group == 0) Operation.INC else Operation.DEC, operands.operand)
            }

            0xff -> {
                val group = (bytes.unsigned(input.position, 1).toInt() ushr 3) and 7
                val operands = modRm(if (group <= 1) width else 8)
                require((!word || group <= 1) && rex and 4 == 0) { "Unsupported indirect branch prefix" }
                result(
                    when (operands.group) {
                        0 -> Operation.INC
                        1 -> Operation.DEC
                        2 -> Operation.CALL
                        4 -> Operation.JMP
                        6 -> Operation.PUSH
                        else -> error("Unsupported x64 indirect group")
                    }, operands.operand
                )
            }

            else -> error("Unsupported x64 opcode: ${opcode.toString(16)}")
        }
    }

    fun all(maximumInstructions: Int = 256): List<Instruction> {
        require(maximumInstructions in 1..65536)
        val instructions = mutableListOf<Instruction>()
        var position = 0L
        while (position < bytes.size) {
            require(instructions.size < maximumInstructions) { "x64 instruction count exceeds bound" }
            val instruction = decode(position)
            instructions += instruction
            position += instruction.size
        }
        return instructions
    }
}
