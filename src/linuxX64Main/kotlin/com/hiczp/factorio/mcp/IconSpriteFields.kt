package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Original icon references selected by enabled-state control flow and consumed by named sprite accessors. */
internal data class IconSpriteFields(
    val normal: Long, val hovered: Long, val disabled: Long, val width: Long, val height: Long,
) {
    companion object {
        private const val CONSTRUCTOR =
            "_ZN10IconButtonC2ERK6SpritePKN4agui11ButtonStyleEPNS3_17GenericTargetableESt8functionIFvvEE"

        fun resolve(image: ElfImage, size: Long): IconSpriteFields {
            val constructor = image.symbol(CONSTRUCTOR)
            val width = image.symbol("_ZN10IconButton14getSpriteWidthEv")
            val table = ItaniumVtable.resolve(image, "_ZTV10IconButton")
            val enabled = table.method(image, "_ZNK4agui6Widget9isEnabledEv")
            val widgetSize = SysVObjectSize.resolve(image, "4agui6Widget")
            SysVAccessors.resolve(image, enabled.function, boolean = true).withinObject(widgetSize)
            EhFrames(image).function(constructor)
            val body = X64ControlFlow.resolve(image, width)
            val members = image.inlines.find(width, "getSpriteWidth", setOf("getWidth", "getHeight"))
                .groupBy { it.name }.mapValues { (_, values) ->
                    values.flatMap { instance -> instance.ranges.map { range ->
                        DwarfRanges.Range(range.start - width.address, range.end - width.address)
                    } }
                }
            return analyze(
                X64ControlFlow.resolve(image, constructor), body,
                image.functionBytes(width, 8192), width.address, size, enabled.slot, members,
            )
        }

        fun analyze(
            constructor: X64ControlFlow, getter: X64ControlFlow, bytes: BinaryView, address: Long,
            size: Long, enabledSlot: Int, members: Map<String, List<DwarfRanges.Range>>,
        ): IconSpriteFields {
            require(size in 8..65536 && enabledSlot in 0..8191 && members.keys == setOf("getWidth", "getHeight"))
            val construction = SysVArgumentFlow(constructor)
            val normal = constructor.instructions.mapNotNull { instruction ->
                if (instruction.offset !in constructor.reachable || instruction.operation != Operation.MOV)
                    return@mapNotNull null
                val source = instruction.source as? Register ?: return@mapNotNull null
                val target = instruction.destination as? Memory ?: return@mapNotNull null
                if (source.width != 8 || target.width != 8 ||
                    construction.register(instruction.offset, source.number) != SysVArgumentFlow.Reference(6))
                    return@mapNotNull null
                construction.memory(instruction.offset, target)?.reference?.takeIf { it.argument == 7 }?.offset
            }.distinct().singleOrNull() ?: error("Icon constructor has no unique original sprite argument member")
            require(normal in 8..size - 8 && normal % 8 == 0L)

            val arguments = SysVArgumentFlow(getter)
            // A null state means unknown provenance. A nonempty set preserves all alternatives at a branch join.
            val before = mutableMapOf<Long, List<Set<Long>?>>(0L to List(32) { null })
            val pending = ArrayDeque<Long>()
            pending.add(0)
            var steps = 0
            while (pending.isNotEmpty()) {
                require(++steps <= 32768) { "Sprite pointer selection exceeds analysis bound" }
                val site = pending.removeFirst()
                val values = before.getValue(site).toMutableList()
                val instruction = getter.body.getValue(site)
                fun read(operand: Operand?): Set<Long>? = (operand as? Register)?.takeIf { it.width == 8 }
                    ?.let { values[it.number] }
                fun write(operand: Operand?, value: Set<Long>?) {
                    if (operand is Register) values[operand.number] = value.takeIf { operand.width == 8 }
                }
                when (instruction.operation) {
                    Operation.MOV -> {
                        val field = arguments.source(site)?.takeIf {
                            it.reference.argument == 7 && it.width == 8 && it.reference.offset >= 8
                        }?.reference?.offset
                        if (field != null) require(field <= size - 8 && field % 8 == 0L)
                        write(instruction.destination, if (field != null) setOf(field) else read(instruction.source))
                    }
                    Operation.CMOV -> {
                        val left = read(instruction.destination)
                        val right = read(instruction.source)
                        write(instruction.destination, if (left != null && right != null) left + right else null)
                    }
                    Operation.XCHG -> {
                        val left = read(instruction.destination)
                        val right = read(instruction.source)
                        write(instruction.destination, right)
                        write(instruction.source, left)
                    }
                    Operation.CALL -> for (register in listOf(0, 1, 2, 6, 7, 8, 9, 10, 11) + (16..31))
                        values[register] = null
                    Operation.PUSH -> values[4] = null
                    Operation.POP -> {
                        values[4] = null
                        write(instruction.destination, null)
                    }
                    Operation.CMP, Operation.TEST, Operation.BIT_TEST, Operation.SCALAR_COMPARE,
                    Operation.JMP, Operation.JCC, Operation.NOP, Operation.ENDBR, Operation.RET -> Unit
                    else -> write(instruction.destination, null)
                }
                for (next in getter.successors.getValue(site)) {
                    val old = before[next]
                    val merged = if (old == null) values.toList() else old.mapIndexed { index, previous ->
                        val current = values[index]
                        if (previous != null && current != null) (previous + current).also { require(it.size <= 16) }
                        else null
                    }
                    if (merged != old) {
                        before[next] = merged
                        pending.add(next)
                    }
                }
            }

            val boundaries = getter.body.keys + getter.instructions.last().let { it.offset + it.size }
            val consumed = members.mapValues { (_, ranges) ->
                require(ranges.isNotEmpty() && ranges.all { it.start in boundaries && it.end in boundaries })
                getter.instructions.filter { instruction ->
                    instruction.offset in getter.reachable && ranges.any {
                        instruction.offset >= it.start && instruction.offset + instruction.size <= it.end
                    } && instruction.operation in setOf(Operation.MOVSX, Operation.MOV)
                }.mapNotNull { instruction ->
                    val source = instruction.source as? Memory ?: return@mapNotNull null
                    val destination = instruction.destination as? Register ?: return@mapNotNull null
                    require(!source.relative && source.index == null && source.width == 2 && destination.width >= 2)
                    val pointers = source.base?.let { before[instruction.offset]?.get(it) }
                        ?: error("Named sprite dimension does not read selected icon pointers")
                    require(pointers.size == 3 && normal in pointers && source.displacement in 0..65534)
                    source.displacement to pointers
                }.distinct().singleOrNull() ?: error("Named sprite dimension has no unique bounded short member")
            }
            val candidates = consumed.values.map { it.second }.distinct().single()
            val provenance = SysVReceiverFlow(bytes, address, size)
            val branches = getter.instructions.filter { instruction ->
                instruction.offset in getter.reachable && SysVVirtualCall.isCandidate(instruction, enabledSlot)
            }.mapNotNull { call ->
                val branch = runCatching { booleanBranch(getter, call) }.getOrNull() ?: return@mapNotNull null
                val values = provenance.borrowedCallValues(call.offset)
                if (SysVVirtualCall.receiver(call, values, enabledSlot) != SysVReceiverFlow.Receiver()) null else branch
            }
            require(branches.isNotEmpty()) { "Icon pointer selection has no verified enabled-state branch" }
            fun selected(enabled: Boolean): Long {
                val offsets = getter.instructions.mapNotNull { instruction ->
                    if (instruction.offset !in getter.reachable || instruction.operation != Operation.MOV)
                        return@mapNotNull null
                    val source = arguments.source(instruction.offset) ?: return@mapNotNull null
                    if (source.reference.argument != 7 || source.width != 8 || source.reference.offset !in candidates)
                        return@mapNotNull null
                    source.reference.offset.takeIf {
                        branches.any { branch ->
                            requiresEdge(getter, instruction.offset, branch.branch, if (enabled) branch.nonzero else branch.zero)
                        }
                    }
                }.distinct()
                return offsets.singleOrNull() ?: error("Icon state has ambiguous source sprite members")
            }
            val hovered = selected(true)
            val disabled = selected(false)
            require(setOf(normal, hovered, disabled) == candidates)
            return IconSpriteFields(normal, hovered, disabled, consumed.getValue("getWidth").first,
                consumed.getValue("getHeight").first)
        }

        private data class Branch(val branch: Long, val zero: Long, val nonzero: Long)

        private fun booleanBranch(flow: X64ControlFlow, call: Instruction): Branch {
            val result = mutableMapOf(0 to 1)
            var tested = false
            var previous = call.offset
            var site = call.offset + call.size
            repeat(64) {
                val instruction = flow.body.getValue(site)
                require(flow.predecessors[site] == setOf(previous)) { "Enabled return joins unrelated control flow" }
                val destination = instruction.destination as? Register
                val source = instruction.source as? Register
                when (instruction.operation) {
                    Operation.MOV, Operation.MOVZX -> if (destination != null) {
                        val width = source?.let { result[it.number]?.takeIf { width -> width >= it.width } }
                        if (width != null && source?.width == 1 && destination.width in listOf(1, 4, 8))
                            result[destination.number] = if (instruction.operation == Operation.MOVZX) destination.width else 1
                        else result.remove(destination.number)
                    }
                    Operation.TEST -> tested = destination != null && destination == source &&
                            (result[destination.number] ?: 0) >= destination.width
                    Operation.CMP -> tested = destination != null && instruction.source == Immediate(0) &&
                            (result[destination.number] ?: 0) >= destination.width
                    Operation.JCC -> {
                        require(tested && instruction.condition in listOf(4, 5))
                        val target = (instruction.destination as Immediate).value
                        val next = instruction.offset + instruction.size
                        return Branch(instruction.offset, if (instruction.condition == 4) target else next,
                            if (instruction.condition == 5) target else next)
                    }
                    Operation.CALL, Operation.RET, Operation.JMP -> error("Enabled return is not tested before transfer")
                    Operation.NOP, Operation.ENDBR -> Unit
                    Operation.LEA -> if (destination != null) result.remove(destination.number)
                    else -> {
                        tested = false
                        if (destination != null) result.remove(destination.number)
                    }
                }
                previous = site
                site += instruction.size
            }
            error("Enabled return use exceeds analysis bound")
        }

        private fun requiresEdge(flow: X64ControlFlow, target: Long, branch: Long, selected: Long): Boolean {
            require(target in flow.reachable && selected in flow.successors.getValue(branch))
            val pending = ArrayDeque<Long>()
            val seen = mutableSetOf<Long>()
            pending.add(0)
            while (pending.isNotEmpty()) {
                val site = pending.removeFirst()
                if (site == target) return false
                if (seen.add(site)) pending.addAll(flow.successors.getValue(site).filterNot { site == branch && it == selected })
            }
            return true
        }
    }
}
