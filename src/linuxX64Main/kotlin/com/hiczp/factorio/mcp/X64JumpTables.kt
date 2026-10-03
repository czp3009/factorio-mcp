package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/**
 * Resolves bounded, guarded, signed-relative switch tables without assuming an event enum or case
 * order.
 */
internal object X64JumpTables {
    data class Table(
        val jump: Long,
        val guard: Long,
        val index: Register,
        val address: Long,
        val targets: List<Long>,
    )

    fun resolve(image: ElfImage, function: ElfImage.Symbol): List<Table> {
        EhFrames(image).function(function)
        require(function.size in 1..32768) { "Switch function exceeds analysis bound" }
        val instructions = X64Instructions(image.functionBytes(function, 32768)).all(8192)
        return resolve(instructions, function.address) { address, size ->
            require(
                image.sections.any { section ->
                    section.flags and 3L == 2L &&
                        address >= section.address &&
                        size <= section.size &&
                        address - section.address <= section.size - size
                }
            ) {
                "Jump table is not in allocated read-only data"
            }
            image.virtualBytes(address, size)
        }
    }

    fun resolve(
        instructions: List<Instruction>,
        address: Long,
        read: (Long, Long) -> BinaryView,
    ): List<Table> {
        require(
            instructions.isNotEmpty() &&
                instructions.size <= 8192 &&
                instructions.first().offset == 0L
        )
        require(instructions.all { it.offset >= 0 && it.size in 1..15 })
        require(
            address >= 0 &&
                instructions.last().offset <= Long.MAX_VALUE - address - instructions.last().size
        )
        require(
            instructions.zipWithNext().all { (left, right) ->
                left.offset + left.size == right.offset
            }
        )
        val body = instructions.associateBy { it.offset }
        val significant =
            instructions.filter { it.operation !in listOf(Operation.NOP, Operation.ENDBR) }
        val chains = mutableMapOf<Long, LongRange>()
        val tables = mutableListOf<Table>()
        for ((position, jump) in significant.withIndex()) {
            if (jump.operation != Operation.JMP || jump.destination is Immediate) continue
            val target = jump.destination as? Register ?: error("Unsupported indirect switch jump")
            require(target.width == 8)
            fun writes(instruction: Instruction, register: Int): Boolean =
                instruction.operation !in
                    listOf(
                        Operation.CMP,
                        Operation.TEST,
                        Operation.BIT_TEST,
                        Operation.SCALAR_COMPARE,
                        Operation.PUSH,
                        Operation.JCC,
                        Operation.JMP,
                        Operation.RET,
                    ) && (instruction.destination as? Register)?.number == register ||
                    instruction.operation == Operation.XCHG &&
                        (instruction.source as? Register)?.number == register ||
                    instruction.operation == Operation.MULTIPLY_WIDE && register in setOf(0, 2) ||
                    instruction.operation == Operation.BYTE_COMPARE_EXCHANGE && register == 0 ||
                    instruction.operation == Operation.ATOMIC_EXCHANGE_ADD &&
                        (instruction.source as? Register)?.number == register
            fun definition(before: Int, register: Int): Int {
                for (candidate in before - 1 downTo maxOf(0, before - 64)) {
                    val instruction = significant[candidate]
                    require(
                        instruction.operation !in
                            listOf(Operation.CALL, Operation.JMP, Operation.JCC, Operation.RET)
                    ) {
                        "Switch calculation crosses an unproven control-flow or call boundary"
                    }
                    if (writes(instruction, register)) return candidate
                }
                error("Switch calculation has no bounded register definition")
            }
            val addIndex = definition(position, target.number)
            val add = significant[addIndex]
            require(add.operation == Operation.ADD && add.destination == target)
            val baseRegister = add.source as? Register ?: error("Switch target has no table base")
            require(baseRegister.width == 8 && baseRegister.number != target.number)
            val loadIndex = definition(addIndex, target.number)
            val load = significant[loadIndex]
            require(load.operation == Operation.MOVSX && load.destination == target)
            val entry = load.source as? Memory ?: error("Switch target is not a signed table entry")
            require(
                !entry.relative &&
                    entry.base == baseRegister.number &&
                    entry.index != null &&
                    entry.index != baseRegister.number &&
                    entry.scale == 4 &&
                    entry.width == 4 &&
                    entry.displacement == 0L
            )
            val baseIndex = definition(loadIndex, baseRegister.number)
            val base = significant[baseIndex]
            require(definition(addIndex, baseRegister.number) == baseIndex) {
                "Switch table base changes after its load"
            }
            require(base.operation == Operation.LEA && base.destination == baseRegister)
            val location = base.source as? Memory ?: error("Switch table has no address")
            require(location.relative && location.base == null && location.index == null)
            val guardIndex =
                (loadIndex - 1 downTo maxOf(0, loadIndex - 64)).firstOrNull {
                    significant[it].operation in
                        listOf(Operation.JCC, Operation.JMP, Operation.CALL, Operation.RET)
                } ?: error("Switch has no bounded guard")
            val guard = significant[guardIndex]
            require(guard.operation == Operation.JCC && guard.condition == 7) {
                "Switch lacks an unsigned upper bound"
            }
            require(
                (guard.destination as? Immediate)?.value?.let {
                    it !in guard.offset + guard.size..jump.offset
                } == true
            ) {
                "Switch overflow path enters the table calculation"
            }
            val comparisonIndex =
                (guardIndex - 1 downTo maxOf(0, guardIndex - 64)).firstOrNull {
                    ScalarExpression.changesFlags(significant[it])
                } ?: error("Switch guard has no bounded flag definition")
            val comparison = significant[comparisonIndex]
            val index =
                comparison.destination as? Register
                    ?: error("Switch bound is not a register comparison")
            val maximum =
                (comparison.source as? Immediate)?.value ?: error("Switch bound is not constant")
            require(
                comparison.operation == Operation.CMP &&
                    index.width in listOf(1, 4, 8) &&
                    index.number !in listOf(4, 5) &&
                    maximum in 0..4095
            ) {
                "Switch exceeds the bounded 4096-entry analysis limit"
            }
            val copyIndex =
                (loadIndex - 1 downTo guardIndex + 1).firstOrNull {
                    writes(significant[it], checkNotNull(entry.index))
                }
            val copiedIndex = copyIndex?.let { significant[it] }
            if (copiedIndex == null) {
                require(index.width in listOf(4, 8) && index.number == entry.index)
                require(
                    (comparisonIndex + 1 until loadIndex).none {
                        writes(significant[it], index.number)
                    }
                ) {
                    "Switch changes its bounded address index"
                }
            } else {
                val width = if (index.width == 8) 8 else 4
                require(
                    (copiedIndex.operation == Operation.MOV && index.width in listOf(4, 8) ||
                        copiedIndex.operation == Operation.MOVZX && index.width == 1) &&
                        copiedIndex.source == index &&
                        copiedIndex.destination == Register(entry.index, width)
                ) {
                    "Switch index is not a full or zero-extended copy of the bounded value"
                }
                require(
                    (comparisonIndex + 1 until checkNotNull(copyIndex)).none {
                        writes(significant[it], index.number)
                    }
                ) {
                    "Switch changes the bounded value before its index copy"
                }
            }
            var chainStart = comparison.offset
            if (index.width == 4 && copiedIndex == null) {
                // A 32-bit comparison cannot bound an unknown high half used by 64-bit address
                // arithmetic.
                var definition = comparisonIndex - 1
                while (definition >= 0 && comparisonIndex - definition <= 32) {
                    val instruction = significant[definition]
                    require(
                        instruction.operation !in
                            listOf(Operation.CALL, Operation.JMP, Operation.JCC, Operation.RET)
                    ) {
                        "Switch index has no local zero extension"
                    }
                    if (writes(instruction, index.number)) break
                    require(
                        instruction.operation != Operation.XCHG ||
                            (instruction.source as? Register)?.number != index.number
                    ) {
                        "Switch index is exchanged"
                    }
                    definition--
                }
                require(definition >= 0 && comparisonIndex - definition <= 32)
                val writer = significant[definition]
                require(
                    writer.destination == index &&
                        writer.operation in
                            listOf(
                                Operation.MOV,
                                Operation.MOVZX,
                                Operation.MOVSX,
                                Operation.LEA,
                                Operation.ADD,
                                Operation.SUB,
                                Operation.AND,
                                Operation.OR,
                                Operation.XOR,
                                Operation.INC,
                                Operation.DEC,
                            )
                ) {
                    "Switch address index has unbounded high bits"
                }
                chainStart = writer.offset
            }
            val next = address + base.offset + base.size
            require(
                location.displacement >= -next && location.displacement <= Long.MAX_VALUE - next
            )
            val tableAddress = next + location.displacement
            val count = maximum.toInt() + 1
            val data = read(tableAddress, count * 4L)
            require(data.size == count * 4L)
            val targets =
                List(count) { index ->
                    val displacement = data.unsigned(index * 4L, 4).toInt().toLong()
                    require(
                        displacement >= -tableAddress &&
                            displacement <= Long.MAX_VALUE - tableAddress
                    )
                    val destination = tableAddress + displacement - address
                    require(destination in body) {
                        "Switch target leaves the function or enters an instruction"
                    }
                    destination
                }
            chains[jump.offset] = chainStart..jump.offset
            tables += Table(jump.offset, guard.offset, index, tableAddress, targets)
        }
        val tableByJump = tables.associateBy { it.jump }
        val predecessors = mutableMapOf<Long, MutableSet<Long>>()
        for (instruction in instructions) {
            val next = instruction.offset + instruction.size
            if (instruction.operation == Operation.CALL)
                require(
                    (instruction.destination as? Immediate)?.value?.let {
                        it != 0L &&
                            it in 0 until instructions.last().offset + instructions.last().size
                    } != true
                ) {
                    "Interior calls are unsupported switch entry paths"
                }
            val targets =
                when (instruction.operation) {
                    Operation.RET -> emptyList()
                    Operation.JMP ->
                        (instruction.destination as? Immediate)?.let { listOf(it.value) }
                            ?: tableByJump.getValue(instruction.offset).targets

                    Operation.JCC ->
                        listOf(
                            next,
                            (instruction.destination as? Immediate)?.value
                                ?: error("Indirect conditional branch"),
                        )

                    else -> listOf(next)
                }
            for (target in targets) if (
                target in 0 until instructions.last().offset + instructions.last().size
            ) {
                require(target in body) { "Branch enters an instruction" }
                predecessors.getOrPut(target) { mutableSetOf() } += instruction.offset
            }
        }
        for (range in chains.values) {
            val chain = instructions.filter { it.offset in range }
            for ((left, right) in chain.zipWithNext()) {
                require(predecessors[right.offset] == setOf(left.offset)) {
                    "Another control-flow edge bypasses the switch bound or table calculation"
                }
            }
        }
        return tables
    }
}
