@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import com.hiczp.factorio.mcp.linuxbridge.FmLinuxModalLayout

/** Native modal-stack/parent predicate, selected by matching inline identities rather than a remembered layout. */
internal data class UiModalMetadata(
    val guiSize: Long,
    val widgetSize: Long,
    val begin: Long,
    val end: Long,
    val stride: Long,
    val target: Long,
    val widgetTargetable: Long,
    val parent: Long,
    private val functions: List<ElfImage.Symbol> = emptyList(),
) {
    fun writeTo(output: FmLinuxModalLayout) {
        output.guiSize = guiSize.toUInt()
        output.widgetSize = widgetSize.toUInt()
        output.begin = begin.toUInt()
        output.end = end.toUInt()
        output.stride = stride.toUInt()
        output.target = target.toUInt()
        output.widgetTargetable = widgetTargetable.toUInt()
        output.parent = parent.toUInt()
    }

    fun verifyLoaded(image: ElfImage, process: ProcessHandle, loadBias: Long) {
        verify(image, loadBias, process::readMemory)
    }

    internal fun verify(image: ElfImage, loadBias: Long, read: (Long, Int) -> ByteArray) {
        require(loadBias >= 0)
        for (function in functions) {
            require(
                function.address >= 0 && function.size in 1..(16 * 1024 * 1024) &&
                        function.address <= Long.MAX_VALUE - loadBias - function.size
            )
            require(
                read(function.address + loadBias, function.size.toInt()).contentEquals(
                    image.functionBytes(function, function.size.toInt()).bytes(0, function.size.toInt())
                )
            ) {
                "Live modal evidence differs from the selected executable"
            }
        }
    }

    companion object {
        fun resolve(image: ElfImage): UiModalMetadata {
            val (resolved, functions) = image.withFunctionEvidence { resolveLayout(image) }
            return resolved.copy(functions = functions)
        }

        private fun resolveLayout(image: ElfImage): UiModalMetadata {
            val guiSize = SysVObjectSize.resolve(image, "4agui3Gui")
            val widgetSize = SysVObjectSize.resolve(image, "4agui6Widget")
            val table = ItaniumVtable.resolve(image, "_ZTVN4agui6WidgetE")
            val parent = SysVRectangleAbi.resolve(
                image, image.symbol("_ZNK4agui6Widget20getAbsoluteRectangleEv"),
                widgetSize, table
            ).parent
            val targetable = SysVTargeterRelease.resolve(
                image,
                "_ZN4agui19GenericTargeterBase8attachToEPNS_17GenericTargetableE"
            ).targetableExtent
            val base = ItaniumClass.resolve(image, "N4agui6WidgetE").directBase(
                ItaniumClass.resolve(image, "N4agui17GenericTargetableE"), widgetSize, targetable
            )
            val function = image.symbol("_ZN4agui3Gui11handleHoverEv")
            val flow = X64ControlFlow.resolve(image, function)
            val inlines =
                image.inlines.find(function, "handleHover", setOf("getModalWidget", "widgetIsModalChild"))
            val predicate = inlines.single { it.name == "widgetIsModalChild" }.ranges.single()
            val getter = inlines.filter {
                it.name == "getModalWidget" && it.ranges.size == 1 &&
                        it.ranges.single().end <= predicate.start
            }.maxByOrNull { it.ranges.single().end }
                ?: error("Modal predicate has no preceding named stack getter")
            val following = inlines.filter {
                it.name == "getModalWidget" && it.ranges.size == 1 &&
                        it.ranges.single().start >= predicate.end
            }.minByOrNull { it.ranges.single().start }
                ?.ranges?.single() ?: error("Modal predicate has no following native absence check")
            SysVPointerGate.resolve(image, function.name, guiSize)
            val gate = flow.instructions.first { it.operation == Operation.JCC }
            val returning = if (gate.condition == 4) gate.offset + gate.size
            else (gate.destination as Immediate).value
            // Tie polarity to the verified early-return arm and the native continuation after the absence check.
            return analyze(
                flow, DwarfRanges.Range(
                    getter.ranges.single().start - function.address,
                    following.end - function.address
                ), guiSize, widgetSize, base, parent,
                mapOf(returning to false, following.end - function.address to true),
                allowWithoutModal = true, coverageEnd = predicate.end - function.address
            )
        }

        fun analyze(
            flow: X64ControlFlow, range: DwarfRanges.Range, guiSize: Long, widgetSize: Long,
            base: Long, parent: Long, resultEdges: Map<Long, Boolean> = emptyMap(),
            allowWithoutModal: Boolean = false, coverageEnd: Long = range.end
        ): UiModalMetadata {
            require(
                guiSize in 8..(16 * 1024 * 1024) && widgetSize in 8..(16 * 1024 * 1024) &&
                        base in 0..widgetSize - 8 && parent in 0..widgetSize - 8
            )
            require(range.start in flow.reachable && range.end > range.start && range.end - range.start <= 2048)
            require(coverageEnd in range.start + 1..range.end && resultEdges.keys.all { it in flow.body } &&
                    (resultEdges.isEmpty() || resultEdges.size == 2 && resultEdges.values.toSet() == setOf(
                        false,
                        true
                    )))
            val instructions = flow.instructions.filter { it.offset in range.start until range.end }
            require(instructions.isNotEmpty() && instructions.last().let { it.offset + it.size } == range.end)
            require(instructions.all { instruction ->
                instruction.operation !in listOf(Operation.JMP, Operation.JCC) ||
                        (instruction.destination as? Immediate)?.value in flow.body
            }) { "Modal predicate branches beyond its enclosing function" }
            val arguments = SysVArgumentFlow(flow)
            val guiRegisters = (0..15).mapNotNull { register ->
                arguments.register(range.start, register)?.takeIf { it.argument == 7 }?.let { register to it.offset }
            }.toMap()
            require(guiRegisters.isNotEmpty())
            val members = instructions.mapNotNull { instruction ->
                if (instruction.operation != Operation.MOV || (instruction.destination as? Register)?.width != 8)
                    return@mapNotNull null
                arguments.source(instruction.offset)?.takeIf {
                    it.reference.argument == 7 && it.width == 8 &&
                            it.reference.offset in 0..guiSize - 8
                }?.reference?.offset
            }.distinct()
            require(members.size == 2) { "Modal stack does not have two bounded GUI pointer members" }
            val strides = instructions.mapNotNull { instruction ->
                val amount = when (instruction.operation) {
                    Operation.ADD, Operation.SUB -> (instruction.source as? Immediate)?.value
                    Operation.LEA -> (instruction.source as? Memory)?.displacement
                    else -> null
                } ?: return@mapNotNull null
                if (amount in -256..-8 || amount in 8..256) kotlin.math.abs(amount).takeIf { it % 8 == 0L } else null
            }.distinct()
            require(strides.isNotEmpty() && strides.size <= 8)
            val body = instructions.associateBy { it.offset }
            val matches = mutableListOf<UiModalMetadata>()
            for (begin in members) for (end in members - begin) for (stride in strides) {
                for (target in 0..stride - 8 step 8) {
                    val candidate = UiModalMetadata(guiSize, widgetSize, begin, end, stride, target, base, parent)
                    for (selected in (0..15).filter { it !in guiRegisters && it !in listOf(4, 5) }) {
                        for (adjustment in setOf(0L, base)) {
                            if (runCatching {
                                    verify(
                                        body, range, guiRegisters, selected, adjustment, candidate,
                                        resultEdges, allowWithoutModal, coverageEnd
                                    )
                                }.isSuccess) matches += candidate
                        }
                    }
                }
            }
            return matches.distinct().singleOrNull() ?: error("Named modal predicate has no unique validated layout")
        }

        private sealed interface Value
        private data class Pointer(val region: Int, val offset: Long = 0) : Value
        private data class Scalar(val number: Long) : Value
        private data object Unknown : Value

        private fun verify(
            body: Map<Long, X64Instructions.Instruction>, range: DwarfRanges.Range,
            guiRegisters: Map<Int, Long>, selectedRegister: Int, selectedAdjustment: Long,
            layout: UiModalMetadata, resultEdges: Map<Long, Boolean>, allowWithoutModal: Boolean,
            coverageEnd: Long
        ) {
            val observed = mutableSetOf<Long>()
            require(body.values.all {
                it.operation in setOf(
                    Operation.NOP, Operation.ENDBR, Operation.MOV,
                    Operation.LEA, Operation.ADD, Operation.SUB, Operation.XOR, Operation.CMP, Operation.TEST,
                    Operation.CMOV, Operation.SET, Operation.JCC, Operation.JMP, Operation.RET
                )
            })
            // Only null/identity comparisons are admitted. Vary every record's identity/null state and both parent
            // relationships to exercise the decoded reverse scan and ancestry loop, including an unrelated lower modal.
            for (count in 0..3) for (mask in 0 until (1 shl count)) for (selected in -1 until count) {
                for (depth in 0..2) {
                    val top = (0 until count).lastOrNull { mask and (1 shl it) != 0 }
                    val expected = if (top == null) allowWithoutModal else selected == top
                    val result = execute(
                        body, range, guiRegisters, selectedRegister, selectedAdjustment,
                        layout, count, mask, selected, depth, observed
                    )
                    val actual = result.second ?: resultEdges[result.first] ?: error("Modal result edge is unverified")
                    require(actual == expected) { "Modal predicate returns another relation" }
                }
            }
            require(body.values.filter {
                it.offset < coverageEnd && it.operation !in listOf(Operation.NOP, Operation.ENDBR) &&
                        !(it.operation == Operation.XOR && it.destination is Register && it.destination == it.source)
            }
                .all { it.offset in observed }) { "Modal predicate has an unvalidated instruction path" }
        }

        private fun execute(
            body: Map<Long, X64Instructions.Instruction>, range: DwarfRanges.Range,
            guiRegisters: Map<Int, Long>, selectedRegister: Int, selectedAdjustment: Long,
            layout: UiModalMetadata, count: Int, mask: Int, selected: Int, depth: Int,
            observed: MutableSet<Long>
        ): Pair<Long, Boolean?> {
            val zero = Scalar(0)
            val vector = Pointer(1)
            val initialEnd = vector.copy(offset = count * layout.stride)
            val memory = mutableMapOf<Pointer, Value>(
                Pointer(0, layout.begin) to vector,
                Pointer(0, layout.end) to initialEnd
            )
            for (index in 0 until count) {
                memory[vector.copy(offset = index * layout.stride + layout.target)] =
                    if (mask and (1 shl index) != 0) Pointer(index + 10, layout.widgetTargetable) else zero
                memory[Pointer(index + 10, layout.parent)] = zero
            }
            var widget = if (selected < 0) Pointer(100) else Pointer(selected + 10)
            memory.getOrPut(widget.copy(offset = layout.parent)) { zero }
            repeat(depth) { level ->
                val child = Pointer(200 + level)
                memory[child.copy(offset = layout.parent)] = widget
                widget = child
            }
            val registers = MutableList<Value>(32) { Unknown }
            for ((register, offset) in guiRegisters) registers[register] = Pointer(0, offset)
            registers[selectedRegister] = widget.copy(offset = selectedAdjustment)
            var equal: Boolean? = null
            var position = range.start
            val visits = mutableMapOf<Long, List<Value>>()
            fun checkEnd() {
                val trimmed = (0 until count).lastOrNull { mask and (1 shl it) != 0 }?.plus(1) ?: 0
                require(
                    memory.getValue(Pointer(0, layout.end)) in setOf(
                        initialEnd,
                        vector.copy(offset = trimmed * layout.stride)
                    )
                ) { "Modal getter prunes a live record" }
            }

            fun address(operand: Memory): Value {
                require(!operand.relative && operand.index == null && operand.displacement in -4096..4096)
                return when (val value = operand.base?.let { registers[it] }) {
                    is Pointer -> value.copy(offset = value.offset + operand.displacement)
                    is Scalar -> Scalar(value.number + operand.displacement)
                    else -> error("Modal predicate uses an unknown address")
                }
            }

            fun read(operand: X64Instructions.Operand?): Value = when (operand) {
                is Immediate -> Scalar(operand.value).also { require(operand.value in 0..1) }
                is Register -> registers[operand.number].also {
                    require(it != Unknown && (operand.width == 8 || it is Scalar))
                }

                is Memory -> {
                    require(operand.width == 8)
                    memory[address(operand)] ?: error("Modal predicate reads beyond its stack or Widget parent fields")
                }

                else -> error("Unsupported modal operand")
            }

            fun write(operand: X64Instructions.Operand?, value: Value) {
                when (operand) {
                    is Register -> {
                        require(
                            operand.number !in listOf(4, 5) &&
                                    (operand.width == 8 || operand.width in listOf(1, 4) && value is Scalar)
                        )
                        registers[operand.number] = value
                    }

                    is Memory -> {
                        require(
                            operand.width == 8 && address(operand) == Pointer(0, layout.end) && value is Pointer &&
                                    value.region == 1 && value.offset in 0..initialEnd.offset && value.offset % layout.stride == 0L
                        )
                        memory[Pointer(0, layout.end)] = value
                    }

                    else -> error("Unsupported modal store")
                }
            }
            repeat(1024) {
                val instruction = body[position]
                if (instruction == null) {
                    checkEnd()
                    return position to null
                }
                visits[position] = registers.toList()
                observed += position
                position += instruction.size
                when (instruction.operation) {
                    Operation.NOP, Operation.ENDBR -> Unit
                    Operation.MOV -> write(instruction.destination, read(instruction.source))
                    Operation.LEA -> write(instruction.destination, address(instruction.source as Memory))
                    Operation.ADD, Operation.SUB -> {
                        val amount = (instruction.source as? Immediate)?.value ?: error("Variable modal pointer step")
                        require(
                            amount in setOf(
                                layout.stride,
                                -layout.stride,
                                layout.widgetTargetable,
                                -layout.widgetTargetable
                            )
                        )
                        val delta = if (instruction.operation == Operation.ADD) amount else -amount
                        val value = when (val value = read(instruction.destination)) {
                            is Pointer -> value.copy(offset = value.offset + delta)
                            else -> error("Modal arithmetic does not adjust a pointer")
                        }
                        write(instruction.destination, value)
                        equal = false
                    }

                    Operation.XOR -> {
                        require(instruction.destination is Register && instruction.destination == instruction.source)
                        write(instruction.destination, zero)
                        equal = true
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

                    Operation.CMOV, Operation.SET -> {
                        require(instruction.condition in listOf(4, 5))
                        val selected = checkNotNull(equal) == (instruction.condition == 4)
                        if (instruction.operation == Operation.SET)
                            write(instruction.destination, Scalar(if (selected) 1 else 0))
                        else if (selected) write(instruction.destination, read(instruction.source))
                    }

                    Operation.JCC, Operation.JMP -> {
                        val target = (instruction.destination as? Immediate)?.value ?: error("Indirect modal branch")
                        if (instruction.operation == Operation.JMP) position = target
                        else {
                            require(instruction.condition in listOf(4, 5))
                            if (checkNotNull(equal) == (instruction.condition == 4)) position = target
                        }
                        if (position in body && position <= instruction.offset) {
                            val previous = visits[position] ?: error("Modal loop enters an unvisited block")
                            require(previous.indices.any { index ->
                                val old = previous[index] as? Pointer ?: return@any false
                                val next = registers[index]
                                old.region == 1 && next is Pointer && next.region == 1 &&
                                        next.offset == old.offset - layout.stride ||
                                        old.region >= 10 && old.offset == 0L &&
                                        next == memory[old.copy(offset = layout.parent)] && next != old
                            }) { "Modal loop does not advance a reverse record scan or Widget parent" }
                        }
                    }

                    Operation.RET -> {
                        checkEnd()
                        val value = registers[0] as? Scalar ?: error("Modal predicate has no boolean return")
                        require(value.number in 0..1)
                        return instruction.offset to (value.number == 1L)
                    }

                    else -> error("Unsupported modal predicate instruction: ${instruction.operation}")
                }
            }
            error("Modal predicate exceeds its bounded scan")
        }
    }
}
