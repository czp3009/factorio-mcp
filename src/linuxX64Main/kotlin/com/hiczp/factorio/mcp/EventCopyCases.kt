package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Selected native byte-copy paths without calls or source mutation. Does not establish destruction semantics. */
internal object EventCopyCases {
    data class Proof(val bytes: Set<Long>, val path: List<Long>, val reads: Set<Long>)
    private data class Pointer(val register: Int, val offset: Long = 0)
    private data class Value(val bytes: List<Long?> = List(16) { null }, val pointer: Pointer? = null)

    fun resolve(image: ElfImage, conversion: SdlButtonConversion): Map<Long, Proof> {
        return resolve(image, conversion.header, conversion.payload.code, conversion.kinds.values.toSet())
    }

    fun resolve(image: ElfImage, header: EventHeader, code: Long, kinds: Set<Long>): Map<Long, Proof> {
        val function = image.symbol("_ZN5Event20emplaceConstructFromERKS_")
        val tables = X64JumpTables.resolve(image, function)
        return analyze(X64ControlFlow.resolve(image, function), tables.single(), header, code, kinds)
    }

    fun analyze(
        flow: X64ControlFlow, table: X64JumpTables.Table, header: EventHeader, code: Long,
        kinds: Set<Long>
    ): Map<Long, Proof> {
        require(header.extent in 16..4096 && kinds.size == 2 && kinds.all { it in 0..0xffffffffL })
        val required = listOf(header.type to 4, header.time to 8, code to 4).flatMap { (offset, width) ->
            require(offset >= 0 && offset <= header.extent - width)
            (offset until offset + width).toList()
        }.toSet()
        return kinds.associateWith { kind ->
            val registers = MutableList(32) { Value(pointer = Pointer(it)) }
            val saved = mutableMapOf<Long, Value>()
            val written = mutableMapOf<Long, Long?>()
            val reads = mutableSetOf<Long>()
            val path = mutableListOf<Long>()
            var comparison: Pair<ULong, ULong>? = null
            var site = 0L
            fun stack(): Long = checkNotNull(registers[4].pointer).also { require(it.register == 4) }.offset
            fun address(memory: Memory): Pointer {
                require(!memory.relative && memory.index == null && memory.displacement in -4096..4096)
                val base = memory.base?.let { registers[it].pointer } ?: error("Copy uses an unknown address")
                return base.copy(offset = base.offset + memory.displacement)
            }

            fun read(operand: Operand?): Value = when (operand) {
                is Register -> registers[operand.number].let {
                    Value(it.bytes.take(operand.width), it.pointer.takeIf { operand.width == 8 })
                }

                is Immediate -> Value(List(8) { -1L - (operand.value ushr (it * 8) and 255) })
                is Memory -> {
                    val pointer = address(operand)
                    require(
                        pointer.register == 6 && operand.width in listOf(1, 2, 4, 8, 16) &&
                                pointer.offset >= 0 && pointer.offset <= header.extent - operand.width
                    ) {
                        "Copy reads outside the original bounded source"
                    }
                    reads.addAll(pointer.offset until pointer.offset + operand.width)
                    Value(List(operand.width) { pointer.offset + it })
                }

                else -> error("Copy has an unsupported value")
            }

            fun number(operand: Operand?, width: Int): ULong {
                require(width in listOf(1, 2, 4, 8))
                val value = read(operand)
                require(value.pointer == null && value.bytes.size >= width)
                return (0 until width).fold(0UL) { result, index ->
                    val byte = checkNotNull(value.bytes[index])
                    val scalar = if (byte < 0) -byte - 1 else {
                        require(byte in header.type until header.type + 4) { "Copy branch depends on payload data" }
                        kind ushr ((byte - header.type).toInt() * 8) and 255
                    }
                    result or (scalar.toULong() shl (index * 8))
                }
            }

            fun write(destination: Operand?, value: Value) {
                when (destination) {
                    is Register -> {
                        require(destination.width in listOf(4, 8, 16))
                        require(value.pointer == null || destination.width == 8)
                        registers[destination.number] = Value(List(16) {
                            if (it < destination.width) value.bytes.getOrNull(it)
                            else if (destination.width == 4 && it < 8) -1L else null
                        }, value.pointer)
                    }

                    is Memory -> {
                        val pointer = address(destination)
                        require(
                            pointer.register == 7 && pointer.offset >= 0 &&
                                    pointer.offset <= header.extent - destination.width && value.pointer == null
                        ) {
                            "Copy mutates its source, leaks a pointer or writes outside its destination"
                        }
                        for (byte in 0 until destination.width) written[pointer.offset + byte] =
                            value.bytes.getOrNull(byte)
                    }

                    else -> error("Copy has an unsupported destination")
                }
            }
            while (true) {
                require(path.size < 256 && site !in path) { "Selected copy loops or exceeds bounds" }
                path += site
                val instruction = flow.body.getValue(site)
                var next = site + instruction.size
                when (instruction.operation) {
                    Operation.PUSH -> {
                        val register = instruction.destination as? Register ?: error("Unsupported copy frame")
                        require(register.width == 8 && register.number != 4)
                        val offset = stack() - 8
                        require(offset in -128..-8)
                        saved[offset] = registers[register.number]
                        registers[4] = Value(pointer = Pointer(4, offset))
                    }

                    Operation.POP -> {
                        val register = instruction.destination as? Register ?: error("Unsupported copy restoration")
                        require(register.width == 8 && register.number != 4)
                        val offset = stack()
                        registers[register.number] = saved.remove(offset) ?: error("Copy restores an unsaved register")
                        registers[4] = Value(pointer = Pointer(4, offset + 8))
                    }

                    Operation.MOV, Operation.SCALAR_MOV, Operation.VECTOR_MOV -> write(
                        instruction.destination,
                        read(instruction.source)
                    )

                    Operation.CMP -> {
                        val width = when (val target = instruction.destination) {
                            is Register -> target.width
                            is Memory -> target.width
                            else -> error("Invalid copy comparison")
                        }
                        comparison = number(instruction.destination, width) to number(instruction.source, width)
                    }

                    Operation.JCC -> {
                        val (left, right) = checkNotNull(comparison)
                        val taken = when (instruction.condition) {
                            2 -> left < right
                            3 -> left >= right
                            4 -> left == right
                            5 -> left != right
                            6 -> left <= right
                            7 -> left > right
                            else -> error("Unsupported copy condition")
                        }
                        if (taken) next = (instruction.destination as Immediate).value
                        else if (site == table.guard) {
                            val index = number(table.index, table.index.width)
                            require(index < table.targets.size.toULong())
                            for (part in flow.instructions.filter { it.offset > site && it.offset <= table.jump }) {
                                if (part.operation != Operation.JMP) {
                                    val target = part.destination as Register
                                    require(target.number in listOf(0, 1, 2, 6, 7, 8, 9, 10, 11))
                                    registers[target.number] = Value()
                                }
                                path += part.offset
                            }
                            comparison = null
                            next = table.targets[index.toInt()]
                        }
                    }

                    Operation.JMP -> next =
                        (instruction.destination as? Immediate)?.value ?: error("Unverified copy switch")

                    Operation.NOP, Operation.ENDBR -> Unit
                    Operation.RET -> {
                        require(stack() == 0L && saved.isEmpty())
                        for (register in listOf(3, 5, 12, 13, 14, 15))
                            require(registers[register] == Value(pointer = Pointer(register))) {
                                "Copy loses a preserved register"
                            }
                        require(written.keys.containsAll(required) && written.all { (offset, value) -> value == offset }) {
                            "Selected copy does not preserve the identified fields byte for byte"
                        }
                        break
                    }

                    else -> error("Selected copy has an unsupported effect: ${instruction.operation}")
                }
                require(
                    next in flow.body && (site == table.guard && next in table.targets ||
                            next in flow.successors.getValue(site))
                )
                site = next
            }
            Proof(written.keys.toSet(), path, reads)
        }
    }
}
