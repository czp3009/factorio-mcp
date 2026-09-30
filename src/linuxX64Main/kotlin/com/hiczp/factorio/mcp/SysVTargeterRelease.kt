package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Proves the null-target arm of a native intrusive-targeter assignment, including list unlinking. */
internal object SysVTargeterRelease {
    data class Layout(val target: Long, val previous: Long, val next: Long, val head: Long) {
        val targeterExtent: Long get() = maxOf(target, previous, next) + 8
        val targetableExtent: Long get() = head + 8
    }

    private sealed interface Value
    private data class Pointer(val region: Int, val offset: Long = 0) : Value
    private data class Saved(val register: Int) : Value
    private data object Zero : Value

    fun resolve(image: ElfImage, name: String): Layout {
        val symbol = image.symbol(name)
        EhFrames(image).function(symbol)
        return analyze(image.functionBytes(symbol, 512))
    }

    /** Additional ABI/field proof for registering a fresh, zeroed adapter-owned targeter. */
    fun verifyFreshAttachment(bytes: BinaryView, layout: Layout) {
        require(bytes.size in 1..512)
        val body = X64Instructions(bytes).all(128).associateBy { it.offset }
        require(body.values.none {
            it.operation == Operation.CALL ||
                    it.operation == Operation.CMOV && it.condition !in listOf(4, 5)
        }) {
            "Fresh targeter assignment has unsupported dispatch or conditional moves"
        }
        for (hasHead in listOf(false, true))
            verify(body, layout, false, false, false, attach = true, hasHead = hasHead)
    }

    /** The native lifetime operation must clear registered records without touching their owners' other storage. */
    fun verifyClearing(bytes: BinaryView, layout: Layout) {
        require(bytes.size in 1..512)
        val body = X64Instructions(bytes).all(128).associateBy { it.offset }
        for (count in 0..3) {
            val owner = Pointer(2)
            val records = (0 until count).map { Pointer(10 + it) }
            val memory =
                mutableMapOf<Pointer, Value>(owner.copy(offset = layout.head) to (records.firstOrNull() ?: Zero))
            for ((index, record) in records.withIndex()) {
                memory[record.copy(offset = layout.target)] = owner
                memory[record.copy(offset = layout.previous)] = records.getOrNull(index - 1) ?: Zero
                memory[record.copy(offset = layout.next)] = records.getOrNull(index + 1) ?: Zero
            }
            val expected = memory.mapValues { Zero }
            execute(body, memory, expected, owner, Saved(6)) { state ->
                records.count { state[it.copy(offset = layout.target)] != Zero }
            }
        }
    }

    fun analyze(bytes: BinaryView): Layout {
        require(bytes.size in 1..512)
        val body = X64Instructions(bytes).all(128)
        val instructions = body.associateBy { it.offset }
        require(body.all { instruction ->
            instruction.operation !in listOf(
                Operation.CALL,
                Operation.CMOV
            ) || instruction.operation == Operation.CMOV &&
                    instruction.condition in listOf(4, 5)
        }) { "Targeter release has unsupported dispatch or conditional moves" }
        val offsets = body.flatMap { instruction ->
            listOfNotNull(instruction.destination as? Memory, instruction.source as? Memory).flatMap {
                if (it.width == 16) listOf(it.displacement, it.displacement + 8) else listOf(it.displacement)
            }
        }.filter { it in 0..248 && it % 8 == 0L }.distinct()
        require(offsets.size in 3..8) { "Targeter field search exceeds bound" }
        val candidates = mutableListOf<Layout>()
        for (target in offsets) for (previous in offsets) for (next in offsets) {
            if (setOf(target, previous, next).size != 3) continue
            for (head in offsets) {
                val layout = Layout(target, previous, next, head)
                if (runCatching {
                        verify(instructions, layout, false, false, false)
                        for (hasPrevious in listOf(false, true)) for (hasNext in listOf(false, true))
                            verify(instructions, layout, true, hasPrevious, hasNext)
                    }.isSuccess) candidates += layout
            }
        }
        return candidates.singleOrNull() ?: error("Native release does not establish one bounded targeter layout")
    }

    private fun verify(
        body: Map<Long, X64Instructions.Instruction>, layout: Layout,
        attached: Boolean, hasPrevious: Boolean, hasNext: Boolean,
        attach: Boolean = false, hasHead: Boolean = false
    ) {
        // Regions are symbolic and distinct. Only null equality is evaluated; no concrete test addresses authorize code.
        val self = Pointer(1)
        val owner = Pointer(2)
        val previous = if (hasPrevious) Pointer(3) else Zero
        val next = if (hasNext) Pointer(4) else Zero
        val memory = mutableMapOf<Pointer, Value>(
            self.copy(offset = layout.target) to if (attached) owner else Zero,
            self.copy(offset = layout.previous) to if (attached) previous else Zero,
            self.copy(offset = layout.next) to if (attached) next else Zero,
        )
        if (attached) {
            memory[owner.copy(offset = layout.head)] = if (hasPrevious) previous else self
            if (previous is Pointer) memory[previous.copy(offset = layout.next)] = self
            if (next is Pointer) memory[next.copy(offset = layout.previous)] = self
        }
        require(!attach || !attached)
        val destination = Pointer(5)
        val oldHead = if (hasHead) Pointer(6) else Zero
        if (attach) {
            memory[destination.copy(offset = layout.head)] = oldHead
            if (oldHead is Pointer) {
                memory[oldHead.copy(offset = layout.previous)] = Zero
                memory[oldHead.copy(offset = layout.target)] = destination
                memory[oldHead.copy(offset = layout.next)] = Zero
            }
        }
        val expected = memory.toMutableMap()
        if (attached) {
            expected[self.copy(offset = layout.target)] = Zero
            expected[self.copy(offset = layout.previous)] = Zero
            expected[self.copy(offset = layout.next)] = Zero
            if (previous is Pointer) expected[previous.copy(offset = layout.next)] = next
            else expected[owner.copy(offset = layout.head)] = next
            if (next is Pointer) expected[next.copy(offset = layout.previous)] = previous
        }
        if (attach) {
            expected[self.copy(offset = layout.target)] = destination
            expected[self.copy(offset = layout.next)] = oldHead
            expected[destination.copy(offset = layout.head)] = self
            if (oldHead is Pointer) expected[oldHead.copy(offset = layout.previous)] = self
        }
        execute(body, memory, expected, self, if (attach) destination else Zero)
    }

    private fun execute(
        body: Map<Long, X64Instructions.Instruction>, memory: MutableMap<Pointer, Value>,
        expected: Map<Pointer, Value>, receiver: Pointer, argument: Value,
        remaining: ((Map<Pointer, Value>) -> Int)? = null
    ) {
        val registers = MutableList<Value>(32) { Saved(it) }
        registers[7] = receiver
        registers[6] = argument
        registers[4] = Pointer(-1)
        var zero: Boolean? = null
        var position = 0L
        val visits = mutableMapOf<Long, Int>()
        fun stack() = (registers[4] as? Pointer)?.also { require(it.region == -1) } ?: error("Unknown release frame")
        fun address(operand: Memory): Pointer {
            require(!operand.relative && operand.index == null)
            val base = operand.base?.let { registers[it] } ?: error("Unknown release address")
            val pointer = when (base) {
                is Pointer -> base.copy(offset = base.offset + operand.displacement)
                Zero -> Pointer(0, operand.displacement)
                else -> error("Release uses an unproven input as a pointer")
            }
            require(pointer.offset in -4096..4096)
            return pointer
        }

        fun location(operand: Memory): Pointer = address(operand).also {
            require(it.region != 0 && it.offset % 8 == 0L)
            if (it.region == -1) require(it.offset >= stack().offset && it.offset + operand.width <= 0)
        }

        fun read(operand: X64Instructions.Operand?): Value = when (operand) {
            is Immediate -> Zero.also { require(operand.value == 0L) }
            is Register -> registers[operand.number].also { require(operand.width == 8 || it == Zero) }
            is Memory -> {
                require(operand.width == 8)
                memory[location(operand)] ?: error("Release reads a field outside the verified list")
            }

            else -> error("Unsupported release operand")
        }

        fun write(target: X64Instructions.Operand?, value: Value) {
            when (target) {
                is Register -> {
                    require(target.width == 8 || value == Zero)
                    if (target.number == 4) require(value is Pointer && value.region == -1 && value.offset in -4096..0)
                    registers[target.number] = value
                }

                is Memory -> {
                    require(target.width == 8 || target.width == 16 && value == Zero)
                    val at = location(target)
                    repeat(target.width / 8) { index ->
                        val cell = at.copy(offset = at.offset + index * 8)
                        require(cell.region == -1 || cell in expected) { "Release writes outside its owned links" }
                        memory[cell] = value
                    }
                }

                else -> error("Unsupported release destination")
            }
        }
        repeat(1024) {
            val measure = remaining?.invoke(memory) ?: 0
            val previous = visits.put(position, measure)
            require(previous == null || remaining != null && measure < previous) {
                "Targeter loop does not clear a registered record"
            }
            val instruction = body[position] ?: error("Release leaves the decoded function")
            position += instruction.size
            when (instruction.operation) {
                Operation.NOP, Operation.ENDBR -> Unit
                Operation.PUSH -> {
                    val value = read(instruction.destination)
                    val top = stack().copy(offset = stack().offset - 8)
                    require(top.offset >= -4096)
                    registers[4] = top
                    memory[top] = value
                }

                Operation.POP -> {
                    val top = stack()
                    require(top.offset < 0 && instruction.destination != Register(4, 8))
                    write(instruction.destination, memory.remove(top) ?: error("Release restores an unknown register"))
                    registers[4] = top.copy(offset = top.offset + 8)
                }

                Operation.MOV, Operation.VECTOR_MOV -> write(instruction.destination, read(instruction.source))
                Operation.LEA -> {
                    val value = address(instruction.source as? Memory ?: error("Invalid release address expression"))
                    write(instruction.destination, if (value == Pointer(0)) Zero else value)
                }

                Operation.XOR, Operation.VECTOR_XOR -> {
                    require(instruction.destination is Register && instruction.destination == instruction.source)
                    write(instruction.destination, Zero)
                    if (instruction.operation == Operation.XOR) zero = true
                }

                Operation.ADD, Operation.SUB -> {
                    val target = instruction.destination as? Register ?: error("Targeter arithmetic writes memory")
                    require(target.width == 8)
                    val base =
                        registers[target.number] as? Pointer ?: error("Targeter arithmetic has no proven pointer")
                    val amount = (instruction.source as? Immediate)?.value ?: error("Dynamic release frame")
                    require(amount in 0..4096 && amount % 8 == 0L)
                    val offset = base.offset + if (instruction.operation == Operation.ADD) amount else -amount
                    require(offset in -4096..4096)
                    write(target, base.copy(offset = offset))
                    zero = null
                }

                Operation.TEST, Operation.CMP -> {
                    val left = read(instruction.destination)
                    val right = read(instruction.source)
                    require(left !is Saved && right !is Saved)
                    require(left == right && instruction.operation == Operation.TEST || left == Zero || right == Zero)
                    zero = if (instruction.operation == Operation.TEST) left == Zero || right == Zero else left == right
                }

                Operation.CMOV -> {
                    require(instruction.condition in listOf(4, 5))
                    val source = read(instruction.source)
                    val flag = checkNotNull(zero)
                    if (if (instruction.condition == 4) flag else !flag) write(instruction.destination, source)
                }

                Operation.JCC, Operation.JMP -> {
                    val target = (instruction.destination as? Immediate)?.value ?: error("Indirect release branch")
                    require((target >= position || remaining != null) && target in body)
                    if (instruction.operation == Operation.JMP) position = target
                    else {
                        require(instruction.condition in listOf(4, 5))
                        val flag = checkNotNull(zero)
                        if (if (instruction.condition == 4) flag else !flag) position = target
                    }
                }

                Operation.RET -> {
                    require(stack() == Pointer(-1)) { "Release leaves an unbalanced frame" }
                    require(
                        listOf(
                            3,
                            5,
                            12,
                            13,
                            14,
                            15
                        ).all { registers[it] == Saved(it) }) { "Release clobbers preserved registers" }
                    require(memory.filterKeys { it.region != -1 } == expected) { "Targeter assignment changes different links" }
                    return
                }

                else -> error("Unsupported targeter release operation: ${instruction.operation}")
            }
        }
        error("Targeter release exceeds instruction bound")
    }
}
