package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/**
 * Existing-key search inside InputState::update. The adapter never invokes the allocating lookup.
 */
internal data class KeyMapLayout(val begin: Long, val end: Long, val stride: Long, val key: Long) {
    private sealed interface Value

    private data class Owner(val offset: Long) : Value

    private data class Event(val offset: Long) : Value

    private data object Code : Value

    private data class Pointer(val field: Long, val advance: Long = 0) : Value

    private data class Distance(val end: Long, val begin: Long) : Value

    private data class Count(val range: Distance, val stride: Long) : Value

    private data class Cell(val pointer: Pointer, val offset: Long) : Value

    private data class Constant(val value: Long) : Value

    companion object {
        fun resolve(image: ElfImage, stateSize: Long, update: InputStateKeyUpdate): KeyMapLayout {
            val function = image.symbol("_ZN10InputState6updateERK5Event")
            val flow = X64ControlFlow.resolve(image, function)
            return listOf(update.press, update.release)
                .map { analyze(flow, stateSize, update, it) }
                .distinct()
                .single()
        }

        fun analyze(
            flow: X64ControlFlow,
            stateSize: Long,
            update: InputStateKeyUpdate,
            case: InputStateKeyUpdate.Case,
        ): KeyMapLayout {
            require(stateSize in 16..16 * 1024 * 1024 && update.requiredValueSize in 1..256)
            val arguments = SysVArgumentFlow(flow)
            val registers = mutableMapOf<Int, Value>()
            for (register in 0..15) arguments.register(case.entry, register)?.let { input ->
                when (input.argument) {
                    7 -> registers[register] = Owner(input.offset)
                    6 -> registers[register] = Event(input.offset)
                }
            }
            var bounds: Distance? = null
            var stride: Long? = null
            var key: Long? = null
            var firstRead = false
            var selectedRead = false
            var lower = false
            var upper = false
            var sizeGuard = false
            var comparison: Pair<Value, Value>? = null
            fun read(operand: Operand?): Value =
                when (operand) {
                    is Immediate -> Constant(operand.value)
                    is Register ->
                        checkNotNull(registers[operand.number]).also {
                            require(
                                operand.width == 8 ||
                                    operand.width == 4 && it == Code ||
                                    operand.width == 4 && it is Cell
                            )
                        }
                    is Memory -> {
                        require(!operand.relative && operand.index == null)
                        when (val base = registers[operand.base]) {
                            is Owner -> {
                                val offset = base.offset + operand.displacement
                                require(
                                    operand.width == 8 &&
                                        offset in update.map..stateSize - 8 &&
                                        offset % 8 == 0L
                                )
                                Pointer(offset - update.map)
                            }
                            is Event -> {
                                require(
                                    operand.width == 4 &&
                                        base.offset + operand.displacement == update.code
                                )
                                Code
                            }
                            is Pointer -> {
                                val range = checkNotNull(bounds)
                                val step = checkNotNull(stride)
                                require(
                                    operand.width == 4 &&
                                        base.field == range.begin &&
                                        operand.displacement in 0..step - 4
                                )
                                require(base.advance == 0L || base.advance == step)
                                if (key == null) key = operand.displacement
                                else require(key == operand.displacement)
                                if (base.advance == 0L) firstRead = true else selectedRead = true
                                Cell(base, operand.displacement)
                            }
                            else -> error("Key search reads an unverified location")
                        }
                    }
                    else -> error("Unsupported key search operand")
                }
            fun write(destination: Operand?, value: Value) {
                val register =
                    destination as? Register ?: error("Existing-key search writes memory")
                require(register.width == 8 || register.width == 4 && value == Code)
                registers[register.number] = value
            }
            fun number(value: Value): Long =
                when (value) {
                    Code -> 2
                    is Constant -> value.value
                    is Count -> 2
                    is Distance -> checkNotNull(stride) * 2
                    is Cell -> if (value.pointer.advance == 0L) 1 else 2
                    is Pointer -> {
                        val range = checkNotNull(bounds)
                        when (value.field) {
                            range.begin -> value.advance
                            range.end -> checkNotNull(stride) * 2 + value.advance
                            else -> error("Key search compares another container")
                        }
                    }
                    else -> error("Key search compares an unverified value")
                }
            fun condition(code: Int, a: Long, b: Long): Boolean =
                when (code) {
                    2 -> a.toULong() < b.toULong()
                    3 -> a.toULong() >= b.toULong()
                    4 -> a == b
                    5 -> a != b
                    6 -> a.toULong() <= b.toULong()
                    7 -> a.toULong() > b.toULong()
                    12 -> a < b
                    13 -> a >= b
                    14 -> a <= b
                    15 -> a > b
                    else -> error("Key search uses an unsupported condition")
                }
            var site = case.entry
            val visits = mutableMapOf<Long, Int>()
            repeat(256) {
                visits[site] = visits.getOrElse(site) { 0 } + 1
                require(visits.getValue(site) <= 2)
                val instruction = flow.body.getValue(site)
                if (site == case.stores.first().site) {
                    val target =
                        instruction.destination as? Memory
                            ?: error("Key update has no record destination")
                    val pointer =
                        registers[target.base] as? Pointer
                            ?: error("Existing key does not reach its update")
                    val range = checkNotNull(bounds)
                    val step = checkNotNull(stride)
                    require(
                        firstRead &&
                            selectedRead &&
                            lower &&
                            upper &&
                            sizeGuard &&
                            pointer == Pointer(range.begin, step)
                    ) {
                        "Existing-key proof is incomplete at $site: first=$firstRead, selected=$selectedRead, " +
                            "lower=$lower, upper=$upper, size=$sizeGuard, cursor=$pointer, range=$range, stride=$step"
                    }
                    require(
                        !target.relative &&
                            target.index == null &&
                            target.displacement == update.held &&
                            target.width == 1 &&
                            update.requiredValueSize <= step
                    )
                    val keyField = checkNotNull(key)
                    require(keyField + 4 <= update.held || update.held + 1 <= keyField)
                    return KeyMapLayout(range.begin, range.end, step, keyField)
                }
                var next = site + instruction.size
                when (instruction.operation) {
                    Operation.MOV -> write(instruction.destination, read(instruction.source))
                    Operation.ADD,
                    Operation.SUB -> {
                        val left = read(instruction.destination)
                        val right = read(instruction.source)
                        val value =
                            if (
                                instruction.operation == Operation.SUB &&
                                    left is Pointer &&
                                    right is Pointer
                            ) {
                                require(
                                    left.advance == 0L &&
                                        right.advance == 0L &&
                                        left.field != right.field
                                )
                                Distance(left.field, right.field).also {
                                    if (bounds == null) bounds = it else require(bounds == it)
                                }
                            } else {
                                val pointer =
                                    left as? Pointer
                                        ?: error("Key cursor has an unverified adjustment")
                                val amount =
                                    (right as? Constant)?.value
                                        ?: error("Key cursor has a variable step")
                                require(
                                    instruction.operation == Operation.ADD &&
                                        amount == stride &&
                                        pointer.advance == 0L
                                )
                                pointer.copy(advance = amount)
                            }
                        write(instruction.destination, value)
                        comparison = null
                    }
                    Operation.SAR -> {
                        val range =
                            read(instruction.destination) as? Distance
                                ?: error("Key count has no pointer distance")
                        val shift =
                            (instruction.source as? Immediate)?.value
                                ?: error("Variable key record width")
                        require(shift in 3..9)
                        val step = 1L shl shift.toInt()
                        if (stride == null) stride = step else require(stride == step)
                        write(instruction.destination, Count(range, step))
                        comparison = null
                    }
                    Operation.CMP ->
                        comparison = read(instruction.destination) to read(instruction.source)
                    Operation.JCC -> {
                        val (left, right) = checkNotNull(comparison)
                        val code = checkNotNull(instruction.condition)
                        val taken = condition(code, number(left), number(right))
                        if (left is Count && right is Constant) {
                            require(left.range == bounds && right.value in 2..4096)
                            sizeGuard = true
                        }
                        val cell = (left as? Cell) ?: (right as? Cell)
                        if (
                            cell != null &&
                                cell.pointer.advance == stride &&
                                (left == Code || right == Code)
                        ) {
                            val selected = if (taken) code else code xor 1
                            if (selected == 4) {
                                lower = true
                                upper = true
                            }
                            if (selected in setOf(3, 13)) {
                                if (right == Code) lower = true else upper = true
                            }
                            if (selected in setOf(6, 14)) {
                                if (right == Code) upper = true else lower = true
                            }
                        }
                        if (taken) next = (instruction.destination as Immediate).value
                    }
                    Operation.JMP ->
                        next =
                            (instruction.destination as? Immediate)?.value
                                ?: error("Indirect key search branch")
                    Operation.NOP,
                    Operation.ENDBR -> Unit
                    else -> error("Unsupported existing-key search: ${instruction.operation}")
                }
                require(next in flow.successors.getValue(site))
                site = next
            }
            error("Existing-key search exceeds bounds")
        }
    }
}
