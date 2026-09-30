package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.SysVReceiverFlow.Original
import com.hiczp.factorio.mcp.SysVReceiverFlow.Receiver
import com.hiczp.factorio.mcp.X64Instructions.*

/** Native state reset associated with unlinking one GUI target on widget destruction. */
internal object GuiTargetReset {
    data class Store(val offset: Long, val width: Int, val value: Long)
    data class Proof(val targeter: Long, val stores: List<Store>)

    private sealed interface Value
    private data class Pointer(val region: Int, val offset: Long = 0) : Value
    private data class Scalar(val value: Long) : Value
    private data object Unknown : Value

    fun resolve(
        image: ElfImage, name: String, guiSize: Long, targetMember: Long,
        widgetBase: Long, layout: SysVTargeterRelease.Layout
    ): Proof {
        val symbol = image.symbol(name)
        EhFrames(image).function(symbol)
        return analyze(image.functionBytes(symbol, 8192), symbol.address, guiSize, targetMember, widgetBase, layout)
    }

    fun analyze(
        bytes: BinaryView, address: Long, guiSize: Long, targetMember: Long,
        widgetBase: Long, layout: SysVTargeterRelease.Layout
    ): Proof {
        val links = listOf(layout.target, layout.previous, layout.next)
        require(links.distinct().size == 3 && (links + layout.head).all { it in 0..248 && it % 8 == 0L })
        val targeter = targetMember - layout.target
        require(guiSize in 8..(16 * 1024 * 1024) && targeter in 0..guiSize - layout.targeterExtent)
        require(widgetBase in 0..(16 * 1024 * 1024))
        val flow = SysVReceiverFlow(bytes, address, guiSize)
        val candidates = flow.instructions.filter { instruction ->
            instruction.operation == Operation.MOV && instruction.destination is Register &&
                    (instruction.source as? Memory)?.let { it.width == 8 && !it.relative && it.index == null } == true &&
                    instruction.offset in flow.reachable
        }.mapNotNull { instruction ->
            val memory = instruction.source as Memory
            val registers = flow.before(instruction.offset)
            val receiver = memory.base?.let { registers[it] } as? Receiver
            if (receiver == null || receiver.adjustment + memory.displacement != targetMember) return@mapNotNull null
            val body = flow.instructions.filter { it.offset >= instruction.offset }.take(128)
            val branch = body.firstOrNull { it.operation == Operation.JCC }
                ?: error("GUI target reset lacks an identity guard")
            val end = (branch.destination as? Immediate)?.value ?: error("Indirect GUI target guard")
            require(end > branch.offset && end - instruction.offset <= 1024)
            val section = body.filter { it.offset < end }.associateBy { it.offset }
            require(section.values.last().let { it.offset + it.size } == end)
            val stores = mutableListOf<List<Store>>()
            for (attached in listOf(false, true)) for (matches in listOf(false, true)) {
                if (!attached && matches) continue // The destroyed Widget argument is nonnull.
                for (previous in listOf(false, true)) for (next in listOf(false, true)) {
                    if (!attached && (previous || next)) continue
                    val reset = verify(
                        section, instruction.offset, end, registers, guiSize, targeter,
                        widgetBase, layout, attached, matches, previous, next
                    )
                    if (matches) stores += reset else require(reset.isEmpty()) { "Reset changes another widget's GUI state" }
                }
            }
            val reset = stores.distinct().singleOrNull() ?: error("GUI reset depends on targeter neighbors")
            require(reset.isNotEmpty()) { "GUI target has no associated constant state reset" }
            Proof(targeter, reset)
        }
        return candidates.singleOrNull() ?: error("GUI destruction does not establish one target state reset")
    }

    private fun verify(
        body: Map<Long, X64Instructions.Instruction>, start: Long, end: Long,
        incoming: List<SysVReceiverFlow.Value>, guiSize: Long, targeter: Long,
        widgetBase: Long, layout: SysVTargeterRelease.Layout,
        attached: Boolean, matches: Boolean, hasPrevious: Boolean, hasNext: Boolean
    ): List<Store> {
        val zero = Scalar(0)
        val self = Pointer(0, targeter)
        val owner = Pointer(1)
        val previous = if (hasPrevious) Pointer(2) else zero
        val next = if (hasNext) Pointer(3) else zero
        val argument = if (matches) owner.copy(offset = -widgetBase) else Pointer(4)
        val memory = mutableMapOf<Pointer, Value>(
            self.copy(offset = targeter + layout.target) to if (attached) owner else zero,
            self.copy(offset = targeter + layout.previous) to if (attached) previous else zero,
            self.copy(offset = targeter + layout.next) to if (attached) next else zero,
        )
        if (attached) {
            memory[owner.copy(offset = layout.head)] = if (hasPrevious) previous else self
            if (previous is Pointer) memory[previous.copy(offset = layout.next)] = self
            if (next is Pointer) memory[next.copy(offset = layout.previous)] = self
        }
        val expected = memory.toMutableMap()
        if (matches) {
            for (member in listOf(layout.target, layout.previous, layout.next))
                expected[self.copy(offset = targeter + member)] = zero
            if (previous is Pointer) expected[previous.copy(offset = layout.next)] = next
            else expected[owner.copy(offset = layout.head)] = next
            if (next is Pointer) expected[next.copy(offset = layout.previous)] = previous
        }
        val registers = incoming.map { value ->
            when (value) {
                is Receiver -> Pointer(0, value.adjustment)
                Original(6) -> argument
                else -> Unknown
            }
        }.toMutableList<Value>()
        val stores = mutableListOf<Store>()
        var equal: Boolean? = null
        var position = start
        fun address(operand: Memory): Value {
            require(!operand.relative && operand.index == null)
            return when (val base = operand.base?.let { registers[it] }) {
                is Pointer -> base.copy(offset = base.offset + operand.displacement)
                is Scalar -> Scalar(base.value + operand.displacement)
                else -> error("GUI reset uses an unproven pointer")
            }
        }

        fun read(operand: X64Instructions.Operand?): Value = when (operand) {
            is Immediate -> Scalar(operand.value)
            is Register -> registers[operand.number].also {
                require(it != Unknown && (operand.width == 8 || it is Scalar))
            }

            is Memory -> {
                require(operand.width == 8)
                memory[address(operand)] ?: error("GUI reset reads outside its native target links")
            }

            else -> error("Unsupported GUI reset operand")
        }

        fun write(operand: X64Instructions.Operand?, value: Value) {
            when (operand) {
                is Register -> {
                    require(operand.number != 4 && (operand.width == 8 || value is Scalar))
                    registers[operand.number] = if (value is Scalar && operand.width in listOf(1, 2, 4))
                        Scalar(value.value and ((1L shl (operand.width * 8)) - 1)) else value
                }

                is Memory -> {
                    val at = address(operand) as? Pointer ?: error("GUI reset writes through null or an integer")
                    if (at in expected) {
                        require(operand.width == 8 || operand.width == 16 && value == zero)
                        repeat(operand.width / 8) { index ->
                            val cell = at.copy(offset = at.offset + index * 8)
                            require(cell in expected)
                            memory[cell] = value
                        }
                    } else {
                        require(at.region == 0 && operand.width in listOf(1, 2, 4, 8) && value is Scalar)
                        require(at.offset in 0..guiSize - operand.width)
                        require(at.offset + operand.width <= targeter || at.offset >= targeter + layout.targeterExtent)
                        require(stores.size < 8 && stores.none {
                            it.offset < at.offset + operand.width && at.offset < it.offset + it.width
                        }) { "GUI reset has overlapping or repeated extra writes" }
                        val bits = if (operand.width == 8) value.value
                        else value.value and ((1L shl (operand.width * 8)) - 1)
                        stores += Store(at.offset, operand.width, bits)
                    }
                }

                else -> error("Unsupported GUI reset destination")
            }
        }
        repeat(128) {
            if (position == end) {
                require(memory == expected) { "GUI destruction does not unlink precisely this targeter" }
                return stores.sortedBy { it.offset }
            }
            val instruction = body[position] ?: error("GUI reset leaves its guarded region")
            position += instruction.size
            when (instruction.operation) {
                Operation.NOP, Operation.ENDBR -> Unit
                Operation.MOV, Operation.VECTOR_MOV -> write(instruction.destination, read(instruction.source))
                Operation.LEA -> write(instruction.destination, address(instruction.source as Memory))
                Operation.XOR, Operation.VECTOR_XOR -> {
                    require(instruction.destination is Register && instruction.destination == instruction.source)
                    write(instruction.destination, zero)
                    if (instruction.operation == Operation.XOR) equal = true
                }

                Operation.CMP, Operation.TEST -> {
                    val left = read(instruction.destination)
                    val right = read(instruction.source)
                    if (instruction.operation == Operation.CMP) equal = left == right
                    else {
                        require(left == right || left == zero || right == zero)
                        equal = left == zero || right == zero
                    }
                }

                Operation.CMOV -> {
                    require(instruction.condition in listOf(4, 5))
                    val source = read(instruction.source)
                    if (checkNotNull(equal) == (instruction.condition == 4)) write(instruction.destination, source)
                }

                Operation.JCC, Operation.JMP -> {
                    val target = (instruction.destination as? Immediate)?.value ?: error("Indirect GUI reset branch")
                    require(target >= position && (target in body || target == end))
                    if (instruction.operation == Operation.JMP) position = target
                    else {
                        require(instruction.condition in listOf(4, 5))
                        if (checkNotNull(equal) == (instruction.condition == 4)) position = target
                    }
                }

                else -> error("Unsupported GUI reset operation: ${instruction.operation}")
            }
        }
        error("GUI reset exceeds its instruction bound")
    }
}
