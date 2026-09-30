package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Typed flat-map record layout from its bounded linear search and existing-key return, without inserting a key. */
internal data class KeyMapLayout(val begin: Long, val end: Long, val stride: Long, val key: Long, val value: Long) {
    private sealed interface Value
    private data class Original(val register: Int) : Value
    private data object Owner : Value
    private data object Code : Value
    private data class Frame(val offset: Long) : Value
    private data class Pointer(val field: Long, val advance: Long = 0) : Value
    private data class Distance(val end: Long, val begin: Long) : Value
    private data class Cell(val pointer: Pointer, val offset: Long) : Value
    private data class Constant(val value: Long) : Value

    companion object {
        fun resolve(image: ElfImage, stateSize: Long, update: InputStateKeyUpdate): KeyMapLayout {
            val layouts = listOf(
                InputStateKeyUpdate.LOOKUP,
                "_ZN7FlatMapI12SDL_ScancodeN10InputState8KeyStateESt4lessIvEE17private_subscriptEOS0_"
            ).map {
                analyze(
                    X64ControlFlow.resolve(image, image.symbol(it)),
                    stateSize - update.map,
                    update.requiredValueSize
                )
            }
            require(layouts.distinct().size == 1) { "Native key lookup overloads disagree on the map layout" }
            return layouts.first()
        }

        fun analyze(flow: X64ControlFlow, ownerSize: Long, valueSize: Long): KeyMapLayout {
            require(ownerSize in 16..(16 * 1024 * 1024) && valueSize in 1..256)
            val registers = (0..15).associateWith<Int, Value> { Original(it) }.toMutableMap()
            registers[7] = Owner
            registers[6] = Code
            registers[4] = Frame(0)
            val stack = mutableMapOf<Long, Value>()
            val visits = mutableMapOf<Long, Int>()
            var bounds: Distance? = null
            var stride: Long? = null
            var key: Long? = null
            var initialCell = false
            var repeatedCell = false
            var sizeGuard: Triple<Int, Long, Boolean>? = null
            var comparison: Pair<Value, Value>? = null
            var equalityChecked = false
            fun top() = (registers[4] as? Frame)?.offset ?: error("Key lookup lost its frame")
            fun read(operand: Operand?): Value = when (operand) {
                is Immediate -> Constant(operand.value)
                is Register -> checkNotNull(registers[operand.number]).also {
                    require(operand.width == 8 || it == Code && operand.width == 4 || it is Cell && operand.width == 4)
                }

                is Memory -> {
                    require(!operand.relative && operand.index == null)
                    when (val base = registers[operand.base]) {
                        Owner -> {
                            require(operand.width == 8 && operand.displacement in 0..ownerSize - 8 && operand.displacement % 8 == 0L)
                            Pointer(operand.displacement)
                        }

                        is Pointer -> {
                            val range =
                                checkNotNull(bounds) { "Key lookup reads a record before establishing its range" }
                            require(operand.width == 4 && base.field == range.begin && operand.displacement in 0..508)
                            if (key == null) key = operand.displacement else require(key == operand.displacement)
                            if (base.advance == 0L) {
                                initialCell = true
                            } else {
                                require(base.advance in 8..512 && base.advance % 8 == 0L)
                                if (stride == null) stride = base.advance else require(stride == base.advance)
                                repeatedCell = true
                            }
                            Cell(base, operand.displacement)
                        }

                        is Frame -> {
                            require(operand.width == 8)
                            checkNotNull(stack[base.offset + operand.displacement])
                        }

                        else -> error("Key lookup reads an unverified location")
                    }
                }

                else -> error("Unknown key lookup operand")
            }

            fun write(destination: Operand?, value: Value) {
                val register = destination as? Register ?: error("Existing-key search writes memory")
                require(register.width == 8 || register.width == 4 && value == Code)
                registers[register.number] = value
            }

            fun condition(code: Int, left: Long, right: Long): Boolean = when (code) {
                2 -> left.toULong() < right.toULong()
                3 -> left.toULong() >= right.toULong()
                4 -> left == right
                5 -> left != right
                6 -> left.toULong() <= right.toULong()
                7 -> left.toULong() > right.toULong()
                12 -> left < right
                13 -> left >= right
                14 -> left <= right
                15 -> left > right
                else -> error("Unsupported key lookup comparison")
            }

            var site = 0L
            repeat(256) {
                visits[site] = visits.getOrElse(site) { 0 } + 1
                require(visits.getValue(site) <= 2) { "Key lookup exceeds its single linear-search iteration" }
                val instruction = flow.body.getValue(site)
                var next = site + instruction.size
                when (instruction.operation) {
                    Operation.PUSH -> {
                        val value = read(instruction.destination)
                        val offset = top() - 8
                        require(offset in -512..-8)
                        registers[4] = Frame(offset)
                        stack[offset] = value
                    }

                    Operation.POP -> {
                        val offset = top()
                        write(instruction.destination, checkNotNull(stack.remove(offset)))
                        registers[4] = Frame(offset + 8)
                    }

                    Operation.MOV -> write(instruction.destination, read(instruction.source))
                    Operation.ADD, Operation.SUB -> {
                        val left = read(instruction.destination)
                        val right = read(instruction.source)
                        val value = if (instruction.operation == Operation.SUB && left is Pointer && right is Pointer) {
                            require(left.advance == 0L && right.advance == 0L && left.field != right.field)
                            Distance(left.field, right.field).also {
                                if (bounds == null) bounds = it else require(bounds == it)
                            }
                        } else {
                            val amount = (right as? Constant)?.value ?: error("Nonconstant key cursor adjustment")
                            val delta = if (instruction.operation == Operation.ADD) amount else -amount
                            when (left) {
                                is Frame -> Frame(left.offset + delta).also { require(it.offset in -512..0) }
                                is Pointer -> left.copy(advance = left.advance + delta).also {
                                    require(delta in 1..512 && it.advance in 1..1024)
                                }

                                else -> error("Unverified key cursor adjustment")
                            }
                        }
                        write(instruction.destination, value)
                        comparison = null
                    }

                    Operation.LEA -> {
                        val source = instruction.source as? Memory ?: error("Unknown key return address")
                        require(!source.relative && source.index == null)
                        val base = registers[source.base] as? Pointer ?: error("Unverified key return address")
                        require(source.displacement in 0..512)
                        write(instruction.destination, base.copy(advance = base.advance + source.displacement))
                    }

                    Operation.CMP -> comparison = read(instruction.destination) to read(instruction.source)
                    Operation.JCC -> {
                        val (left, right) = checkNotNull(comparison)
                        val code = checkNotNull(instruction.condition)
                        val taken = when {
                            left is Distance && right is Constant -> {
                                require(
                                    left == bounds && sizeGuard == null && code in setOf(
                                        6,
                                        7
                                    ) && right.value in 16..65536
                                )
                                condition(code, 0, right.value).also { sizeGuard = Triple(code, right.value, it) }
                            }

                            left is Pointer && right is Pointer -> {
                                val range = checkNotNull(bounds)
                                require(
                                    code in setOf(4, 5) && setOf(left.field, right.field) == setOf(
                                        range.begin,
                                        range.end
                                    )
                                )
                                require(listOf(left, right).all { it.field == range.begin || it.advance == 0L })
                                // The selected path has two records and searches for the second one.
                                condition(code, 0, 1)
                            }

                            left is Cell && right == Code -> {
                                require(code in setOf(12, 13, 14, 15, 4, 5))
                                if (left.pointer.advance > 0 && repeatedCell && visits.getValue(site) == 1)
                                    equalityChecked = true
                                condition(code, if (left.pointer.advance == 0L) 0 else 1, 1)
                            }

                            else -> error("Key lookup has an unverified branch")
                        }
                        if (taken) next =
                            (instruction.destination as? Immediate)?.value ?: error("Indirect key lookup branch")
                    }

                    Operation.JMP -> next =
                        (instruction.destination as? Immediate)?.value ?: error("Indirect key lookup branch")

                    Operation.RET -> {
                        val range = checkNotNull(bounds)
                        val step = checkNotNull(stride)
                        val returned = registers[0] as? Pointer ?: error("Key lookup does not return record storage")
                        val value = returned.advance - step
                        val field = checkNotNull(key)
                        require(
                            returned.field == range.begin && initialCell && repeatedCell && equalityChecked &&
                                    field in 0..step - 4 && value in 0..step - valueSize &&
                                    (field + 4 <= value || value + valueSize <= field)
                        )
                        val guard = checkNotNull(sizeGuard)
                        require(condition(guard.first, step * 2, guard.second) == guard.third)
                        require(top() == 0L && listOf(3, 5, 12, 13, 14, 15).all { registers[it] == Original(it) })
                        return KeyMapLayout(range.begin, range.end, step, field, value)
                    }

                    Operation.NOP, Operation.ENDBR -> Unit
                    else -> error("Unsupported existing-key search operation: ${instruction.operation}")
                }
                require(next in flow.successors.getValue(site))
                site = next
            }
            error("Existing-key search exceeds bounds")
        }
    }
}
