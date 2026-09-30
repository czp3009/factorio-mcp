package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Proves selected successful poll results are copied without an owning-payload destructor or another call. */
internal object PollEventCopies {
    data class Proof(val bytes: Set<Long>, val engaged: Long, val path: List<Long>)
    private data class Pointer(val frame: Boolean, val offset: Long)
    private data class Value(val bytes: List<Long?> = emptyList(), val pointer: Pointer? = null)

    fun resolve(image: ElfImage, poll: EventPollCall, header: EventHeader, kinds: Set<Long>): Map<Long, Proof> {
        val tables = X64JumpTables.resolve(image, poll.caller)
        val flow = X64ControlFlow.resolve(image, poll.caller)
        val call = flow.instructions.single { it.offset + it.size == poll.returnOffset }
        return analyze(flow, tables, header, call.offset, kinds)
    }

    fun analyze(
        flow: X64ControlFlow, tables: List<X64JumpTables.Table>, header: EventHeader,
        call: Long, kinds: Set<Long>
    ): Map<Long, Proof> {
        require(
            header.extent in 16..4096 && header.type in 0..header.extent - 4L &&
                    header.time in 0..header.extent - 8L && kinds.size in 1..8 && kinds.all { it in 0..0xffffffffL })
        require(flow.body[call]?.operation == Operation.CALL)
        val prefix = X64ControlFlow(flow.instructions.takeWhile { it.offset <= call })
        val frame = SysVLocalArgument(prefix)
        val local = frame.argument(call, 6, header.extent)
        val values = ConstructorValues(prefix, mapOf(call to listOf(ConstructorValues.Borrow(6, header.extent))))
        val preserved = listOf(3, 5, 12, 13, 14, 15)
        fun literal(value: Long, width: Int) = List(width) { -1L - (value ushr (it * 8) and 255) }
        return kinds.associateWith { kind ->
            val registers = MutableList(32) { Value() }
            for (register in preserved + 4) {
                val offset = frame.registers(call)[register]
                val argument = values.register(call, register) as? ConstructorValues.Argument
                registers[register] = when {
                    offset != null -> Value(pointer = Pointer(true, offset))
                    argument?.register == 7 -> Value(pointer = Pointer(false, argument.adjustment))
                    else -> Value()
                }
            }
            registers[0] = Value(literal(1, 1))
            val output = mutableMapOf<Long, Long?>()
            val path = mutableListOf<Long>()
            var comparison: Pair<ULong, ULong>? = null
            var site = call + flow.body.getValue(call).size
            fun top() = checkNotNull(registers[4].pointer).also { require(it.frame) }.offset
            fun address(memory: Memory): Pointer {
                require(!memory.relative && memory.index == null && memory.displacement in -16384..16384)
                val base = memory.base?.let { registers[it].pointer } ?: error("Poll result uses an unknown address")
                return base.copy(offset = base.offset + memory.displacement)
            }

            fun eventByte(offset: Long): Long = if (offset in header.type until header.type + 4)
                literal(kind, 4)[(offset - header.type).toInt()] else offset

            fun read(operand: Operand?, width: Int): Value = when (operand) {
                is Register -> registers[operand.number].let {
                    Value(it.bytes.take(operand.width), it.pointer.takeIf { operand.width == 8 })
                }

                is Immediate -> Value(literal(operand.value, width))
                is Memory -> {
                    val pointer = address(operand)
                    require(
                        pointer.frame && pointer.offset >= local &&
                                pointer.offset <= local + header.extent - operand.width && pointer.offset >= top()
                    ) {
                        "Poll result reads outside its bounded local Event"
                    }
                    Value(List(operand.width) { eventByte(pointer.offset - local + it) })
                }

                else -> error("Unsupported poll result operand")
            }

            fun number(operand: Operand?, width: Int): ULong {
                require(width in listOf(1, 2, 4, 8))
                val value = read(operand, width)
                require(value.pointer == null && value.bytes.size >= width)
                return (0 until width).fold(0UL) { sum, index ->
                    val byte = checkNotNull(value.bytes[index])
                    require(byte < 0) { "Poll result branches on an unknown payload byte" }
                    sum or ((-1 - byte).toULong() shl (index * 8))
                }
            }

            fun write(operand: Operand?, value: Value) {
                when (operand) {
                    is Register -> {
                        require(operand.width in listOf(1, 2, 4, 8, 16))
                        val old = registers[operand.number]
                        registers[operand.number] = Value(List(16) { index ->
                            when {
                                index < operand.width -> value.bytes.getOrNull(index)
                                operand.number < 16 && operand.width == 4 && index < 8 -> -1L
                                operand.number < 16 && operand.width < 4 -> old.bytes.getOrNull(index)
                                else -> null
                            }
                        }, value.pointer.takeIf { operand.width == 8 })
                    }

                    is Memory -> {
                        val target = address(operand)
                        require(
                            !target.frame && target.offset >= 0 &&
                                    target.offset <= header.extent + 8 - operand.width && value.pointer == null
                        ) {
                            "Poll result mutates its Event, exposes a pointer or exceeds its output bound"
                        }
                        repeat(operand.width) { output[target.offset + it] = value.bytes.getOrNull(it) }
                    }

                    else -> error("Unsupported poll result destination")
                }
            }

            fun condition(code: Int): Boolean {
                val (left, right) = checkNotNull(comparison) { "Poll result uses unknown flags" }
                return when (code) {
                    2 -> left < right
                    3 -> left >= right
                    4 -> left == right
                    5 -> left != right
                    6 -> left <= right
                    7 -> left > right
                    else -> error("Unsupported poll result condition")
                }
            }
            while (true) {
                require(path.size < 256 && site !in path) { "Poll result loops or exceeds its bound" }
                path += site
                val instruction = flow.body.getValue(site)
                val target = instruction.destination
                val width = when (target) {
                    is Register -> target.width; is Memory -> target.width; else -> 0
                }
                var next = site + instruction.size
                var switched = false
                when (instruction.operation) {
                    Operation.MOV, Operation.SCALAR_MOV, Operation.VECTOR_MOV -> write(
                        target,
                        read(instruction.source, width)
                    )

                    Operation.LEA -> write(target, Value(pointer = address(instruction.source as Memory)))
                    Operation.TEST, Operation.CMP -> {
                        val left = number(target, width)
                        val right = number(instruction.source, width)
                        comparison =
                            if (instruction.operation == Operation.TEST) (left and right) to 0UL else left to right
                    }

                    Operation.AND -> {
                        val result = number(target, width) and number(instruction.source, width)
                        write(target, Value(literal(result.toLong(), width)))
                        comparison = result to 0UL
                    }

                    Operation.ADD, Operation.SUB -> {
                        require(target == Register(4, 8) && instruction.source is Immediate)
                        val amount = instruction.source.value
                        require(amount in 0..16384 && amount % 8 == 0L)
                        val adjusted = top() + if (instruction.operation == Operation.ADD) amount else -amount
                        require(adjusted in -16384..0)
                        registers[4] = Value(pointer = Pointer(true, adjusted))
                        comparison = null
                    }

                    Operation.POP -> {
                        require(target is Register && target.width == 8 && target.number in preserved)
                        registers[target.number] = Value()
                        val adjusted = top() + 8
                        require(adjusted in -16384..0)
                        registers[4] = Value(pointer = Pointer(true, adjusted))
                    }

                    Operation.JCC -> {
                        if (condition(checkNotNull(instruction.condition))) next = (target as Immediate).value
                        else tables.singleOrNull { it.guard == site }?.let { table ->
                            val index = number(table.index, table.index.width)
                            require(index < table.targets.size.toULong())
                            for (part in flow.instructions.filter { it.offset > site && it.offset <= table.jump }) {
                                if (part.operation != Operation.JMP) {
                                    val destination = part.destination as Register
                                    require(destination.number in listOf(0, 1, 2, 6, 7, 8, 9, 10, 11))
                                    registers[destination.number] = Value()
                                }
                                path += part.offset
                            }
                            comparison = null
                            next = table.targets[index.toInt()]
                            switched = true
                        }
                    }

                    Operation.JMP -> next = (target as? Immediate)?.value ?: error("Unverified poll result switch")
                    Operation.NOP, Operation.ENDBR -> Unit
                    Operation.RET -> {
                        require(top() == 0L)
                        break
                    }

                    else -> error("Poll result has an unsupported effect: ${instruction.operation}")
                }
                require(next in flow.body && (switched || next in flow.successors.getValue(site)))
                site = next
            }
            val engaged = output.filter { (offset, value) -> offset >= header.extent && value == -2L }.keys.single()
            require(output.filterKeys { it >= header.extent }.keys == setOf(engaged))
            val copied = output.filterKeys { it < header.extent }
            require(copied.all { (offset, value) -> value == eventByte(offset) }) {
                "Poll result does not preserve selected Event bytes"
            }
            require((header.type until header.type + 4).all { it in copied } &&
                    (header.time until header.time + 8).all { it in copied })
            Proof(copied.keys, engaged, path)
        }
    }
}
