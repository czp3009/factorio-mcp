package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Exhaustive bounded message-length paths through the constructor's string-copy suffix. */
internal object ChatStringCopy {
    private sealed interface Value
    private data class Number(val value: Long) : Value
    private data class Object(val offset: Long) : Value
    private data class Source(val offset: Long) : Value
    private data class Bytes(val offset: Long) : Value
    private data class Heap(val offset: Long) : Value
    private data class Byte(val offset: Long) : Value

    fun verify(image: ElfImage, size: Long, payload: Long, string: NativeStringLayout): Set<Long> {
        val entry =
            image.symbol("_ZN11InputActionC2E15InputActionTypeRKNSt7__cxx1112basic_stringIcSt11char_traitsIcESaIcEEE")
        val flow = X64ControlFlow.resolve(image, entry)
        val allocate = image.symbol("_Znwm")
        EhFrames(image).function(allocate)
        val copies = flow.instructions.filter { it.operation == Operation.CALL }.mapNotNull {
            (it.destination as? Immediate)?.value?.takeIf { target ->
                image.importedFunction(entry.address + target) == "memcpy"
            }
        }.distinct()
        return analyze(flow, size, payload, string, allocate.address - entry.address, copies.single())
    }

    fun analyze(
        flow: X64ControlFlow, size: Long, payload: Long, string: NativeStringLayout,
        allocate: Long, copy: Long, maximum: Int = 4096
    ): Set<Long> {
        require(size in 1..4096 && payload in 0..size - string.size && maximum in 1..4096 && allocate != copy)
        val arguments = SysVArgumentFlow(flow)
        val start = flow.instructions.single {
            it.offset in flow.reachable && it.operation == Operation.LEA &&
                    arguments.source(it.offset)?.reference == SysVArgumentFlow.Reference(7, payload + string.local)
        }.offset
        val beforeCopy = ArrayDeque<Long>()
        val checked = mutableSetOf<Long>()
        beforeCopy.add(0)
        while (beforeCopy.isNotEmpty()) {
            val at = beforeCopy.removeFirst()
            if (at == start || !checked.add(at)) continue
            require(flow.body.getValue(at).operation != Operation.RET) {
                "Constructor can return normally without constructing its string payload"
            }
            beforeCopy.addAll(flow.successors.getValue(at))
        }
        val frame = SysVLocalArgument(flow).registers(start)[4] ?: error("Copy suffix has no established frame")
        require((8 + frame) % 16 == 0L)
        val initial = (0..15).map { register ->
            arguments.register(start, register)?.let {
                when (it.argument) {
                    7 -> Object(it.offset)
                    2 -> Source(it.offset)
                    else -> null
                }
            }
        }
        val indexed = mutableSetOf<Long>()
        for (length in 0..maximum) {
            val registers = initial.toMutableList<Value?>()
            var data: Value? = null
            var allocated = false
            var capacity = false
            var copied = length == 0
            var copiedTo: Value? = null
            var terminated = false
            var terminatedTo: Value? = null
            var sized = false
            var comparison: Pair<Long, Long>? = null
            var site = start
            val visited = mutableSetOf<Long>()
            fun number(value: Value?) = (value as? Number)?.value ?: error("Copy uses an unproven scalar")
            fun fits(value: Value?) = value == Heap(0) && allocated ||
                    value == Object(payload + string.local) && length < string.size - string.local

            fun adjusted(value: Value?, amount: Long): Value = when (value) {
                is Object -> Object(value.offset + amount)
                is Source -> Source(value.offset + amount)
                is Bytes -> Bytes(value.offset + amount)
                is Heap -> Heap(value.offset + amount)
                else -> error("Copy uses an unproven pointer")
            }

            fun location(memory: Memory): Value {
                require(!memory.relative && memory.base != null && memory.displacement in -4096..4096)
                if (memory.index != null) indexed += site
                val index = memory.index?.let { number(registers[it]) * memory.scale } ?: 0
                return adjusted(registers[memory.base], memory.displacement + index)
            }

            fun read(operand: Operand?): Value = when (operand) {
                is Immediate -> Number(operand.value)
                is Register -> when (val value = checkNotNull(registers[operand.number])) {
                    is Number -> {
                        require(operand.width in listOf(1, 2, 4, 8))
                        if (operand.width == 8) value else
                            Number(value.value and ((1L shl (operand.width * 8)) - 1))
                    }

                    is Byte -> value.also { require(operand.width in listOf(1, 4)) }
                    else -> value.also { require(operand.width == 8) }
                }

                is Memory -> when (val at = location(operand)) {
                    is Source -> {
                        require(operand.width == 8)
                        when (at.offset) {
                            string.data -> Bytes(0)
                            string.length -> Number(length.toLong())
                            else -> error("Copy reads an unrelated source-string field")
                        }
                    }

                    is Bytes -> {
                        require(operand.width == 1 && at.offset in 0 until length.toLong())
                        Byte(at.offset)
                    }

                    else -> error("Copy reads unknown memory")
                }

                else -> error("Unsupported copy operand")
            }

            fun write(operand: Operand?, value: Value) {
                if (operand is Register) {
                    require(
                        operand.number !in listOf(4, 5) &&
                                (operand.width == 8 || operand.width == 4 && (value is Number || value is Byte))
                    )
                    registers[operand.number] = if (value is Number && operand.width == 4)
                        Number(value.value and 0xffffffffL) else value
                    return
                }
                val memory = operand as? Memory ?: error("Copy has no destination")
                val at = location(memory)
                if (at is Object && memory.width == 8) {
                    when (at.offset) {
                        payload + string.data -> {
                            require(
                                value == Object(payload + string.local) && data == null ||
                                        value == Heap(0) && allocated && data == Object(payload + string.local)
                            )
                            data = value
                        }

                        payload + string.length -> {
                            require(value == Number(length.toLong()) && !sized)
                            sized = true
                        }

                        payload + string.local -> {
                            require(allocated && value == Number(length.toLong()) && !capacity)
                            capacity = true
                        }

                        else -> error("Copy writes outside string fields")
                    }
                } else {
                    require(memory.width == 1 && data != null)
                    if (value == Number(0)) {
                        require(
                            at == adjusted(data, length.toLong()) && !terminated &&
                                    fits(data)
                        )
                        terminated = true
                        terminatedTo = data
                    } else {
                        require(length == 1 && value == Byte(0) && at == data && !copied)
                        copied = true
                        copiedTo = data
                    }
                }
            }
            while (true) {
                require(visited.size < 256 && visited.add(site)) { "Copy suffix loops or exceeds bounds" }
                val instruction = flow.body.getValue(site)
                var next = site + instruction.size
                when (instruction.operation) {
                    Operation.LEA -> write(instruction.destination, location(instruction.source as Memory))
                    Operation.MOV, Operation.MOVZX -> write(instruction.destination, read(instruction.source))
                    Operation.CMP -> comparison =
                        number(read(instruction.destination)) to number(read(instruction.source))

                    Operation.TEST -> {
                        require(instruction.destination == instruction.source)
                        comparison = number(read(instruction.destination)) to 0L
                    }

                    Operation.INC -> {
                        val result = number(read(instruction.destination)) + 1
                        require(result in 0..4097)
                        write(instruction.destination, Number(result))
                        comparison = result to 0L
                    }

                    Operation.JCC -> {
                        val (left, right) = checkNotNull(comparison)
                        val take = when (instruction.condition) {
                            2 -> left.toULong() < right.toULong()
                            3 -> left.toULong() >= right.toULong()
                            4 -> left == right
                            5 -> left != right
                            8 -> left - right < 0
                            9 -> left - right >= 0
                            else -> error("Unsupported string-copy condition")
                        }
                        if (take) next = (instruction.destination as Immediate).value
                    }

                    Operation.JMP -> next = (instruction.destination as Immediate).value
                    Operation.CALL -> {
                        val target = (instruction.destination as? Immediate)?.value
                        val result = when (target) {
                            allocate -> {
                                require(
                                    !allocated && data == Object(payload + string.local) &&
                                            registers[7] == Number(length + 1L)
                                )
                                allocated = true
                                Heap(0)
                            }

                            copy -> {
                                require(
                                    registers[7] == data && registers[6] == Bytes(0) &&
                                            registers[2] == Number(length.toLong()) && (!copied || length == 0) &&
                                            fits(data)
                                )
                                copied = true
                                copiedTo = data
                                checkNotNull(data)
                            }

                            else -> error("Copy suffix calls an unverified function")
                        }
                        for (register in listOf(0, 1, 2, 6, 7, 8, 9, 10, 11)) registers[register] = null
                        registers[0] = result
                        comparison = null
                    }

                    Operation.POP, Operation.RET -> {
                        var cursor = site
                        while (flow.body.getValue(cursor).operation == Operation.POP) {
                            val pop = flow.body.getValue(cursor)
                            require(
                                pop.destination is Register && pop.destination.width == 8 &&
                                        pop.destination.number in listOf(3, 5, 12, 13, 14, 15)
                            )
                            cursor += pop.size
                        }
                        require(
                            flow.body.getValue(cursor).operation == Operation.RET &&
                                    copied && (length == 0 || copiedTo == data) && terminated && terminatedTo == data &&
                                    sized && capacity == allocated &&
                                    data == if (allocated) Heap(0) else Object(payload + string.local)
                        ) {
                            "Copy suffix does not finish an independently owned, terminated string"
                        }
                        break
                    }

                    Operation.NOP, Operation.ENDBR -> Unit
                    else -> error("Unsupported string-copy instruction: ${instruction.operation}")
                }
                require(next in flow.successors.getValue(site))
                site = next
            }
        }
        return indexed
    }
}
