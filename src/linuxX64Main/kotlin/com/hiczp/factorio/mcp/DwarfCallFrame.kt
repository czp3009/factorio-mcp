package com.hiczp.factorio.mcp

/** Bounded x64 DWARF call-frame rows. Unsupported rules fail instead of scanning stack contents. */
internal object DwarfCallFrame {
    private sealed interface Rule {
        data object Undefined : Rule
        data object Same : Rule
        data class Offset(val value: Long, val indirect: Boolean = true) : Rule
        data class Register(val number: Int) : Rule
    }

    private data class Row(
        var register: Int? = null,
        var offset: Long = 0,
        var expression: BinaryView? = null,
        val rules: MutableMap<Int, Rule> = mutableMapOf(),
    ) {
        fun snapshot() = copy(rules = rules.toMutableMap())
    }

    private fun add(left: Long, right: Long): Long {
        require(if (right >= 0) left <= Long.MAX_VALUE - right else left >= Long.MIN_VALUE - right) {
            "Unwind arithmetic overflow"
        }
        return left + right
    }

    private fun scale(value: Long, factor: Long): Long {
        require(factor != 0L && !(value == Long.MIN_VALUE && factor == -1L))
        val result = value * factor
        require(result / factor == value) { "Unwind scaled offset overflow" }
        return result
    }

    /** Arithmetic CFA expressions, including linker PLT rows; no branches or external memory reads. */
    private fun expression(bytes: BinaryView, registers: Map<Int, Long>): Long {
        require(bytes.size in 1..1024) { "Unwind expression exceeds bound" }
        val cursor = bytes.cursor()
        val stack = ArrayDeque<Long>()
        fun push(value: Long) {
            require(stack.size < 64) { "Unwind expression stack exceeds bound" }
            stack.addLast(value)
        }

        fun pop(): Long = stack.removeLastOrNull() ?: error("Unwind expression stack underflow")
        fun binary(operation: (Long, Long) -> Long) {
            val right = pop()
            push(operation(pop(), right))
        }

        fun base(number: Int) = push(
            add(
                registers[number] ?: error("Unwind expression needs an unavailable register"),
                cursor.sleb(),
            )
        )

        var count = 0
        while (cursor.remaining > 0) {
            require(++count <= 256) { "Unwind expression operation count exceeds bound" }
            when (val opcode = cursor.unsigned(1).toInt()) {
                0x10 -> push(cursor.uleb().also { require(it >= 0) })
                0x11 -> push(cursor.sleb())
                0x1a -> binary { left, right -> left and right }
                0x22 -> binary(::add)
                0x24 -> binary { left, right ->
                    require(right in 0..63 && left >= 0 && left <= (Long.MAX_VALUE shr right.toInt())) {
                        "Unwind expression shift exceeds address range"
                    }
                    left shl right.toInt()
                }

                0x2a -> binary { left, right -> if (left >= right) 1 else 0 }
                in 0x30..0x4f -> push((opcode - 0x30).toLong())
                in 0x70..0x8f -> base(opcode - 0x70)
                0x92 -> base(cursor.uleb().also { require(it in 0..255) }.toInt())
                else -> error("Unsupported x64 unwind expression instruction: $opcode")
            }
        }
        require(stack.size == 1) { "Unwind expression did not produce one CFA" }
        return stack.single()
    }

    fun caller(
        frame: EhFrames.Frame,
        pc: Long,
        registers: Map<Int, Long>,
        read: (Long) -> Long,
    ): Map<Int, Long> {
        require(pc in frame.start until frame.end && frame.common.returnRegister == 16L)
        require(!frame.common.signalFrame) { "Signal-frame unwind is not supported for frontend admission" }
        var row = Row()
        // Only the System V callee-saved integer registers survive an unspecified caller rule.
        for (register in listOf(3, 6, 12, 13, 14, 15)) row.rules[register] = Rule.Same
        var initial = row.snapshot()

        fun execute(bytes: BinaryView, initialProgram: Boolean) {
            require(bytes.size <= 16384) { "Unwind program exceeds bound" }
            val cursor = bytes.cursor()
            var location = frame.start
            val saved = ArrayDeque<Row>()
            fun register(): Int = cursor.uleb().also {
                require(it in 0..255) { "Unsupported unwind register" }
            }.toInt()

            fun advance(amount: Long): Boolean {
                require(!initialProgram && amount >= 0)
                val next = add(location, scale(amount, frame.common.codeAlignment))
                require(next in location..frame.end) { "Unwind row escapes its function" }
                if (next > pc) return false
                location = next
                return true
            }

            var count = 0
            while (cursor.remaining > 0) {
                require(++count <= 8192)
                val opcode = cursor.unsigned(1).toInt()
                when (opcode and 0xc0) {
                    0x40 -> if (!advance((opcode and 0x3f).toLong())) return
                    0x80 -> row.rules[opcode and 0x3f] = Rule.Offset(scale(cursor.uleb(), frame.common.dataAlignment))
                    0xc0 -> row.rules[opcode and 0x3f] = initial.rules[opcode and 0x3f] ?: Rule.Undefined
                    else -> when (opcode) {
                        0x00 -> Unit
                        0x02, 0x03, 0x04 -> if (!advance(cursor.unsigned(1 shl (opcode - 2)))) return
                        0x05 -> row.rules[register()] = Rule.Offset(scale(cursor.uleb(), frame.common.dataAlignment))
                        0x06 -> {
                            val number = register()
                            row.rules[number] = initial.rules[number] ?: Rule.Undefined
                        }

                        0x07 -> row.rules[register()] = Rule.Undefined
                        0x08 -> row.rules[register()] = Rule.Same
                        0x09 -> row.rules[register()] = Rule.Register(register())
                        0x0a -> {
                            require(saved.size < 64) { "Unwind saved-row stack exceeds bound" }
                            saved.addLast(row.snapshot())
                        }

                        0x0b -> row = saved.removeLastOrNull() ?: error("Unwind row stack underflow")
                        0x0c -> {
                            row.register = register()
                            row.offset = cursor.uleb().also { require(it >= 0) }
                            row.expression = null
                        }

                        0x0d -> {
                            require(row.register != null)
                            row.register = register()
                        }

                        0x0e -> {
                            require(row.register != null)
                            row.offset = cursor.uleb().also { require(it >= 0) }
                        }

                        0x0f -> {
                            row.expression = cursor.block(cursor.uleb())
                            row.register = null
                        }

                        0x11 -> row.rules[register()] = Rule.Offset(scale(cursor.sleb(), frame.common.dataAlignment))
                        0x12 -> {
                            row.register = register()
                            row.offset = scale(cursor.sleb(), frame.common.dataAlignment)
                            row.expression = null
                        }

                        0x13 -> {
                            require(row.register != null)
                            row.offset = scale(cursor.sleb(), frame.common.dataAlignment)
                        }

                        0x14 -> row.rules[register()] =
                            Rule.Offset(scale(cursor.uleb(), frame.common.dataAlignment), false)

                        0x15 -> row.rules[register()] =
                            Rule.Offset(scale(cursor.sleb(), frame.common.dataAlignment), false)

                        0x2e -> require(cursor.uleb() >= 0) // GNU argument size does not change the frame row.
                        else -> error("Unsupported x64 unwind instruction: $opcode")
                    }
                }
            }
        }

        execute(frame.common.instructions, true)
        initial = row.snapshot()
        execute(frame.instructions, false)
        val cfa = row.expression?.let { expression(it, registers) } ?: run {
            val base = registers[checkNotNull(row.register) { "Missing unwind CFA rule" }]
                ?: error("Unwind CFA depends on an unavailable register")
            add(base, row.offset)
        }
        require(cfa > checkNotNull(registers[7]) && cfa % 8 == 0L) { "Unwind stack did not advance" }
        val caller = mutableMapOf<Int, Long>()
        for (number in 0..16) {
            val value = when (val rule = row.rules[number] ?: Rule.Undefined) {
                Rule.Undefined -> null
                Rule.Same -> registers[number]
                is Rule.Register -> registers[rule.number]
                is Rule.Offset -> add(cfa, rule.value).let { if (rule.indirect) read(it) else it }
            }
            if (value != null) caller[number] = value
        }
        caller[7] = cfa
        return caller
    }
}
