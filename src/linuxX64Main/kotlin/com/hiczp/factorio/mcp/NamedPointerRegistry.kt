package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Read-only pointer registry and string fields from the initialized native string-view lookup. */
internal data class NamedPointerRegistry(val begin: Long, val end: Long, val nameData: Long, val nameSize: Long) {
    private sealed interface Value
    private data class Original(val register: Int) : Value
    private data class Frame(val offset: Long) : Value
    private data class Pointer(val field: Long, val advance: Long = 0) : Value
    private data class Entry(val index: Int) : Value
    private data class Field(val entry: Entry, val offset: Long) : Value
    private data class Constant(val number: Long) : Value
    private data object NameData : Value
    private data object NameSize : Value
    private data object Ready : Value

    companion object {
        fun resolve(
            image: ElfImage,
            lookup: String,
            registry: String,
            guard: String,
            objectSize: Long
        ): NamedPointerRegistry {
            val function = image.symbol(lookup)
            val owner = image.symbol(registry)
            val initialized = image.symbol(guard)
            require(
                function.size in 1..4096 && owner.type == 1 && owner.size in 16..4096 &&
                        initialized.type == 1 && initialized.size in 1..8
            )
            EhFrames(image).function(function)
            val flow = X64ControlFlow(X64Instructions(image.functionBytes(function, 4096)).all(1024))
            val comparisons = flow.instructions.filter { it.operation == Operation.CALL }.mapNotNull {
                (it.destination as? Immediate)?.value?.plus(function.address)
            }.filter { image.importedFunction(it) in setOf("bcmp", "memcmp") }.toSet()
            require(comparisons.size == 1) { "Registry lookup has no unique imported byte comparison" }
            return analyze(
                flow, function.address, owner.address, owner.size, initialized.address,
                objectSize, comparisons.single()
            )
        }

        fun analyze(
            flow: X64ControlFlow, address: Long, registry: Long, registrySize: Long, guard: Long,
            objectSize: Long, comparison: Long
        ): NamedPointerRegistry {
            require(registrySize in 16..4096 && objectSize in 16..16 * 1024 * 1024)
            val begins = mutableSetOf<Long>()
            val ranges = mutableSetOf<Set<Long>>()
            val data = mutableSetOf<Long>()
            val sizes = mutableSetOf<Long>()
            val advanced = mutableSetOf<Long>()
            // Empty, first/last/missing matches, length mismatch and byte mismatch exercise both search loops.
            for (count in 0..3) for (match in -1 until count) for (empty in listOf(false, true))
                for (lengthMismatch in listOf(false, true)) {
                    val registers = MutableList<Value>(16) { Original(it) }
                    registers[7] = NameSize
                    registers[6] = NameData
                    registers[4] = Frame(0)
                    val frame = mutableMapOf<Long, Value>()
                    val lengthsChecked = mutableSetOf<Entry>()
                    val bytesChecked = mutableSetOf<Entry>()
                    var zero: Boolean? = null
                    var readyChecked = false
                    var returned = false
                    var site = 0L
                    fun top() = (registers[4] as? Frame)?.offset ?: error("Registry lookup lost its frame")
                    fun read(operand: Operand?, next: Long): Value = when (operand) {
                        is Immediate -> Constant(operand.value)
                        is Register -> registers[operand.number].also {
                            require(
                                operand.width == 8 || operand.width in setOf(
                                    1,
                                    4
                                ) && (it == Ready || it is Constant)
                            )
                        }

                        is Memory -> {
                            require(operand.index == null)
                            if (operand.relative) {
                                val location = address + next + operand.displacement
                                if (location == guard) {
                                    require(operand.width == 1)
                                    Ready
                                } else {
                                    require(
                                        readyChecked && operand.width == 8 && location - registry in 0..registrySize - 8 &&
                                                (location - registry) % 8 == 0L
                                    )
                                    Pointer(location - registry)
                                }
                            } else when (val base = operand.base?.let { registers[it] }) {
                                is Pointer -> {
                                    require(
                                        operand.width == 8 && operand.displacement == 0L && base.advance % 8 == 0L &&
                                                base.advance / 8 in 0 until count.toLong()
                                    )
                                    begins += base.field
                                    Entry((base.advance / 8).toInt())
                                }

                                is Entry -> {
                                    require(
                                        operand.width == 8 && operand.displacement in 0..objectSize - 8 &&
                                                operand.displacement % 8 == 0L
                                    )
                                    Field(base, operand.displacement)
                                }

                                is Frame -> {
                                    require(operand.width == 8)
                                    checkNotNull(frame[base.offset + operand.displacement])
                                }

                                else -> error("Registry lookup reads an unverified location")
                            }
                        }

                        else -> error("Unsupported registry lookup operand")
                    }

                    fun write(operand: Operand?, value: Value) {
                        val target = operand as? Register ?: error("Registry lookup writes memory")
                        require(target.width == 8 || target.width == 4 && (value is Constant || value == Ready))
                        registers[target.number] = value
                    }

                    fun equal(left: Value, right: Value): Boolean = when {
                        left == Ready && (right == Ready || right == Constant(0)) || right == Ready && left == Constant(
                            0
                        ) -> {
                            readyChecked = true
                            false // TEST of the initialized guard is nonzero.
                        }

                        left == NameSize && right == NameSize -> empty // TEST of the string-view length.
                        left is Constant && right is Constant -> left.number == right.number
                        left is Pointer && right is Pointer -> {
                            require(left.field != right.field && (left.advance == 0L || right.advance == 0L))
                            ranges += setOf(left.field, right.field)
                            maxOf(left.advance, right.advance) == count * 8L
                        }

                        left is Field || right is Field -> {
                            val field = if (left is Field) left else right as Field
                            val other = if (left is Field) right else left
                            require(other == NameSize || empty && other == Constant(0))
                            sizes += field.offset
                            (field.entry.index == match || !empty && !lengthMismatch).also {
                                if (it) lengthsChecked += field.entry
                            }
                        }

                        else -> error("Registry lookup compares unverified values")
                    }
                    repeat(512) {
                        if (returned) return@repeat
                        val instruction = flow.body.getValue(site)
                        var next = site + instruction.size
                        when (instruction.operation) {
                            Operation.NOP, Operation.ENDBR -> Unit
                            Operation.MOV, Operation.MOVZX -> write(
                                instruction.destination,
                                read(instruction.source, next)
                            )

                            Operation.PUSH -> {
                                val value = read(instruction.destination, next)
                                val offset = top() - 8
                                require(offset in -512..-8)
                                frame[offset] = value
                                registers[4] = Frame(offset)
                            }

                            Operation.POP -> {
                                val offset = top()
                                write(instruction.destination, checkNotNull(frame.remove(offset)))
                                registers[4] = Frame(offset + 8)
                            }

                            Operation.ADD, Operation.SUB -> {
                                val left = read(instruction.destination, next)
                                val amount =
                                    (instruction.source as? Immediate)?.value ?: error("Nonconstant registry cursor")
                                val delta = if (instruction.operation == Operation.ADD) amount else -amount
                                val value = when (left) {
                                    is Frame -> Frame(left.offset + delta).also {
                                        require(it.offset in -512..0)
                                        if (delta > 0) frame.keys.filter { key -> key in left.offset until it.offset }
                                            .forEach(frame::remove)
                                    }

                                    is Pointer -> left.copy(advance = left.advance + delta).also {
                                        require(delta == 8L && it.advance <= count * 8L)
                                        advanced += left.field
                                    }

                                    else -> error("Registry lookup changes an unverified pointer")
                                }
                                write(instruction.destination, value)
                                zero = null
                            }

                            Operation.CMP -> zero =
                                equal(read(instruction.destination, next), read(instruction.source, next))

                            Operation.TEST -> {
                                require(instruction.destination == instruction.source)
                                val value = read(instruction.destination, next)
                                zero = if (value is Constant) value.number == 0L else equal(value, value)
                            }

                            Operation.XOR -> {
                                require(instruction.destination == instruction.source)
                                write(instruction.destination, Constant(0))
                                zero = true
                            }

                            Operation.CALL -> {
                                require(
                                    (instruction.destination as? Immediate)?.value?.plus(address) == comparison &&
                                            top() % 16 == -8L && registers[7] == NameData && registers[2] == NameSize && !empty
                                )
                                val field = registers[6] as? Field ?: error("Byte comparison loses the native name")
                                require(field.entry in lengthsChecked)
                                data += field.offset
                                bytesChecked += field.entry
                                for (register in listOf(0, 1, 2, 6, 7, 8, 9, 10, 11)) registers[register] = Original(-1)
                                registers[0] = Constant(if (field.entry.index == match) 0 else 1)
                                zero = null
                            }

                            Operation.JCC -> {
                                require(instruction.condition in setOf(4, 5))
                                if (checkNotNull(zero) == (instruction.condition == 4)) next =
                                    (instruction.destination as? Immediate)?.value ?: error("Indirect registry branch")
                            }

                            Operation.JMP -> next = (instruction.destination as? Immediate)?.value
                                ?: error("Indirect registry jump")

                            Operation.RET -> {
                                require(readyChecked && registers[0] == if (match < 0) Constant(0) else Entry(match))
                                if (match >= 0) require(Entry(match) in lengthsChecked && (empty || Entry(match) in bytesChecked))
                                require(top() == 0L && frame.isEmpty() && listOf(3, 5, 12, 13, 14, 15).all {
                                    registers[it] == Original(it)
                                })
                                returned = true
                            }

                            else -> error("Unsupported registry lookup operation: ${instruction.operation}")
                        }
                        if (!returned) require(next in flow.successors.getValue(site))
                        site = next
                    }
                    require(returned) { "Registry lookup exceeds its bounded search" }
                }
            val begin = begins.single()
            val end = (ranges.single() - begin).single()
            require(advanced == setOf(begin) && data.single() != sizes.single())
            return NamedPointerRegistry(begin, end, data.single(), sizes.single())
        }
    }
}
