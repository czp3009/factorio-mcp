package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/**
 * Fresh straight-line writes to a local reference argument. Field names/enum meanings require separate evidence.
 * Any returned literal ranges must also match the loaded executable before their values authorize runtime use.
 */
internal class LocalAggregate(
    private val flow: X64ControlFlow,
    private val address: Long = 0,
    private val readLiteral: (Long, Int) -> BinaryView? = { _, _ -> null },
) {
    data class Constant(val offset: Long, val width: Int, val value: Long)
    data class LiteralRange(val address: Long, val bytes: List<Int>)
    data class Field(val offset: Long, val width: Int)
    data class Proof(
        val call: Long, val constants: List<Constant>, val receiverFields: List<Long>,
        val literals: List<LiteralRange>, val written: List<Field>
    )

    private sealed interface Value {
        val width: Int
    }

    private data class Literal(val bytes: List<Int>) : Value {
        override val width get() = bytes.size
    }

    private data class Opaque(val identity: Int, override val width: Int) : Value
    private data class Write(val address: Long, val width: Int, val value: Value)

    private val frame = SysVLocalArgument(flow)

    init {
        val last = flow.instructions.last()
        require(address >= 0 && address <= Long.MAX_VALUE - last.offset - last.size)
    }

    /** A complete named inline construction with only private output stores and a straight path to its consumer. */
    fun constructedAt(call: Long, extent: Int, ranges: List<DwarfRanges.Range>, argument: Int = 6): Proof {
        require(ranges.isNotEmpty())
        val start = ranges.minOf { it.start }
        val end = ranges.maxOf { it.end }
        require(start in flow.reachable && start < end && end <= call)
        require(ranges.all { it.start in flow.body && it.end in flow.body })
        val storage = frame.argument(call, argument, extent)
        var cursor = start
        var previous: Long? = null
        var count = 0
        val stores = mutableListOf<Field>()
        while (cursor != call) {
            require(++count <= 256)
            val instruction = flow.body.getValue(cursor)
            require(previous == null || flow.predecessors[cursor] == setOf(previous)) {
                "Inline construction has an intervening entry edge"
            }
            require(
                instruction.operation !in listOf(
                    Operation.CALL, Operation.RET, Operation.JCC, Operation.JMP,
                    Operation.PUSH, Operation.POP, Operation.XCHG
                )
            ) { "Inline construction has a callback or control transfer" }
            val next = cursor + instruction.size
            require(next <= call && flow.successors.getValue(cursor) == listOf(next))
            val within = ranges.any { it.start <= cursor && next <= it.end }
            require(ranges.none { it.start in cursor + 1 until next || it.end in cursor + 1 until next })
            val reads = listOfNotNull(
                instruction.source as? Memory,
                (instruction.destination as? Memory).takeIf {
                    instruction.operation in listOf(Operation.CMP, Operation.TEST)
                })
            if (instruction.operation != Operation.LEA) for (read in reads) {
                val target = frame.address(cursor, read)
                require(target == null || target + read.width <= storage || target >= storage + extent) {
                    "Inline construction reads its previous output"
                }
            }
            val memory = instruction.destination as? Memory
            if (memory != null && instruction.operation !in listOf(Operation.CMP, Operation.TEST, Operation.NOP)) {
                require(instruction.operation in listOf(Operation.MOV, Operation.SCALAR_MOV, Operation.VECTOR_MOV)) {
                    "Inline construction modifies an existing output value"
                }
                val target = frame.address(cursor, memory) ?: error("Inline construction writes outside its frame")
                require(within && target >= storage && target <= storage + extent - memory.width) {
                    "Inline construction has a store outside its named output"
                }
                stores += Field(target - storage, memory.width)
            }
            previous = cursor
            cursor = next
        }
        val proof = analyze(call, extent, argument, 7, partialWrites = true)
        fun coverage(fields: List<Field>) =
            fields.flatMap { (offset, width) -> (offset until offset + width).toList() }.toSet()
        require(stores.isNotEmpty() && coverage(stores) == coverage(proof.written)) {
            "Inline construction has inherited or invalidated fields"
        }
        return proof
    }

    fun at(call: Long, extent: Int, argument: Int = 6, receiver: Int = 7): Proof =
        analyze(call, extent, argument, receiver, partialWrites = false)

    private fun analyze(call: Long, extent: Int, argument: Int, receiver: Int, partialWrites: Boolean): Proof {
        require(receiver in listOf(7, 6, 2, 1, 8, 9) && receiver != argument)
        val storage = frame.argument(call, argument, extent)
        val suffix = ArrayDeque<Long>()
        var cursor = call
        while (suffix.size < 256) {
            val previous = flow.predecessors[cursor]?.singleOrNull() ?: break
            val instruction = flow.body.getValue(previous)
            if (previous >= cursor || instruction.operation in listOf(
                    Operation.CALL, Operation.JCC,
                    Operation.JMP, Operation.RET
                )
            ) break
            suffix.addFirst(previous)
            cursor = previous
        }
        var identity = 0
        fun unknown(width: Int) = Opaque(++identity, width)
        val registers = MutableList<Value>(32) { unknown(if (it < 16) 8 else 16) }
        val writes = mutableListOf<Write>()
        val bytes = mutableMapOf<Long, Int>()
        val literals = mutableListOf<LiteralRange>()
        fun number(value: Long, width: Int): Literal {
            require(width <= 8 || value == 0L)
            return Literal(List(width) { if (it < 8) (value ushr (it * 8) and 255).toInt() else 0 })
        }

        fun narrowed(value: Value, width: Int): Value = when {
            value.width < width -> unknown(width)
            value is Literal -> Literal(value.bytes.take(width))
            else -> (value as Opaque).copy(width = width)
        }
        for (position in suffix) {
            val instruction = flow.body.getValue(position)
            fun read(operand: X64Instructions.Operand?, width: Int): Value = when (operand) {
                is Immediate -> number(operand.value, width)
                is Register -> narrowed(registers[operand.number], operand.width)
                // No cached load or saved local value survives into this proof. A load only creates a fresh token.
                is Memory -> {
                    val next = address + position + instruction.size
                    val data = if (operand.relative && operand.base == null && operand.index == null &&
                        operand.displacement >= -next && operand.displacement <= Long.MAX_VALUE - next
                    )
                        readLiteral(next + operand.displacement, operand.width) else null
                    if (data == null) unknown(operand.width) else {
                        require(data.size == operand.width.toLong())
                        val content = List(operand.width) { data.unsigned(it.toLong(), 1).toInt() }
                        literals += LiteralRange(next + operand.displacement, content)
                        Literal(content)
                    }
                }

                else -> unknown(width)
            }

            fun write(operand: X64Instructions.Operand?, value: Value) {
                when (operand) {
                    is Register -> {
                        val part = narrowed(value, operand.width)
                        registers[operand.number] = if (operand.number < 16 && operand.width == 4) {
                            if (part is Literal) Literal(part.bytes + List(4) { 0 }) else unknown(8)
                        } else part
                    }

                    is Memory -> {
                        val address = frame.address(position, operand)
                        if (address == null) {
                            bytes.clear() // An escaped local pointer may alias any earlier local write.
                        } else {
                            val stack = checkNotNull(frame.registers(position)[4])
                            require(address >= stack && address <= -operand.width) { "Aggregate store exceeds its local frame" }
                            writes += Write(address, operand.width, narrowed(value, operand.width))
                            for (byte in 0 until operand.width) bytes[address + byte] = writes.lastIndex
                        }
                    }

                    else -> error("Unsupported aggregate destination")
                }
            }

            val destinationWidth = when (val destination = instruction.destination) {
                is Register -> destination.width
                is Memory -> destination.width
                else -> 8
            }
            when (instruction.operation) {
                Operation.MOV, Operation.VECTOR_MOV, Operation.SCALAR_MOV ->
                    write(instruction.destination, read(instruction.source, destinationWidth))

                Operation.MOVZX -> {
                    val value = read(instruction.source, destinationWidth)
                    write(
                        instruction.destination, if (value is Literal)
                            Literal(value.bytes + List(destinationWidth - value.width) { 0 })
                        else unknown(destinationWidth)
                    )
                }

                Operation.XOR, Operation.VECTOR_XOR -> write(
                    instruction.destination,
                    if (instruction.destination is Register && instruction.destination == instruction.source)
                        number(0, destinationWidth) else unknown(destinationWidth)
                )

                Operation.XCHG -> {
                    val left = read(instruction.destination, destinationWidth)
                    val right = read(instruction.source, destinationWidth)
                    write(instruction.destination, right)
                    write(instruction.source, left)
                }

                Operation.CMOV -> {
                    val left = read(instruction.destination, destinationWidth)
                    val right = read(instruction.source, destinationWidth)
                    write(instruction.destination, if (left == right) left else unknown(destinationWidth))
                }

                Operation.CMP, Operation.TEST, Operation.SCALAR_COMPARE, Operation.NOP, Operation.ENDBR -> Unit
                Operation.PUSH, Operation.POP -> {
                    bytes.clear()
                    if (instruction.operation == Operation.POP) write(
                        instruction.destination,
                        unknown(destinationWidth)
                    )
                }

                else -> write(instruction.destination, unknown(destinationWidth))
            }
        }
        val live = writes.withIndex().flatMap { (index, write) ->
            if (write.address < storage || write.address + write.width > storage + extent) return@flatMap emptyList()
            val surviving = (0 until write.width).filter { bytes[write.address + it] == index }.toSet()
            if (surviving.size == write.width) return@flatMap listOf(write)
            if (!partialWrites) return@flatMap emptyList()
            buildList {
                var byte = 0
                while (byte < write.width) {
                    if (byte !in surviving) {
                        ++byte
                        continue
                    }
                    val width = listOf(8, 4, 2, 1).first { width ->
                        (byte until byte + width).all { it in surviving }
                    }
                    val value = if (write.value is Literal) Literal(write.value.bytes.subList(byte, byte + width))
                    else unknown(width)
                    add(Write(write.address + byte, width, value))
                    byte += width
                }
            }
        }
        val constants = live.flatMap { write ->
            val value = write.value as? Literal ?: return@flatMap emptyList()
            require(write.width in listOf(1, 2, 4, 8, 16))
            value.bytes.chunked(8).mapIndexed { index, part ->
                val value =
                    part.foldIndexed(0L) { byte, result, content -> result or (content.toLong() shl (byte * 8)) }
                Constant(write.address - storage + index * 8, part.size, value)
            }
        }
        val receiverValue = registers[receiver]
        val receiverFields = live.filter { it.width == 8 && receiverValue is Opaque && it.value == receiverValue }
            .map { it.address - storage }
        return Proof(
            call, constants.sortedBy { it.offset }, receiverFields.sorted(), literals.distinct(),
            live.map { Field(it.address - storage, it.width) }.sortedBy { it.offset })
    }

    companion object {
        fun resolve(image: ElfImage, function: ElfImage.Symbol): LocalAggregate {
            val flow = X64ControlFlow.resolve(image, function)
            return LocalAggregate(flow, function.address) { address, width ->
                if (image.sections.any { section ->
                        section.flags and 3L == 2L && address >= section.address && width <= section.size &&
                                address - section.address <= section.size - width
                    }) image.virtualBytes(address, width.toLong()) else null
            }
        }
    }
}
