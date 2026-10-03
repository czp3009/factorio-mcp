package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Cached native SurfaceView fields and the selected packed pixel/map getter's System V boundary. */
internal data class ViewportCoordinates(
    val surface: Long, val position: Long, val fractionBits: Int, val mapPosition: ElfImage.Symbol,
) {
    companion object {
        fun resolve(image: ElfImage, viewSize: Long): ViewportCoordinates {
            val prepare = image.symbol("_ZN8GameView13prepareRenderEv")
            val map = image.symbol("_ZNK8GameView14getMapPositionE13PixelPosition")
            val calculate = image.symbol("_ZNK8GameView20calculateSurfaceViewEv")
            val prepareFlow = X64ControlFlow.resolve(image, prepare)
            val mapFlow = X64ControlFlow.resolve(image, map)
            fun member(function: ElfImage.Symbol, name: String, flow: X64ControlFlow, inline: String, width: Int): Long {
                val ranges = image.inlines.find(function, name, setOf(inline)).flatMap { it.ranges }.map {
                    DwarfRanges.Range(it.start - function.address, it.end - function.address)
                }
                return InlineScalarMember.analyze(flow, ranges, viewSize, width)
            }
            val position = member(map, "getMapPosition", mapFlow, "getCenter", 8)
            val calculateFlow = X64ControlFlow.resolve(image, calculate)
            val outputArguments = SysVArgumentFlow(calculateFlow)
            val outputEnd = calculateFlow.instructions.mapNotNull { instruction ->
                val memory = instruction.destination as? Memory ?: return@mapNotNull null
                if (instruction.operation !in setOf(Operation.MOV, Operation.SCALAR_MOV, Operation.VECTOR_MOV)) return@mapNotNull null
                val reference = outputArguments.memory(instruction.offset, memory) ?: return@mapNotNull null
                if (reference.reference.argument != 7) return@mapNotNull null
                require(reference.reference.offset >= 0 && reference.reference.offset <= 4096 - memory.width)
                reference.reference.offset + memory.width
            }.maxOrNull() ?: error("SurfaceView getter does not write return storage")
            val center = image.symbol("_ZN16RenderParameters8centerOnERK3MapRK11SurfaceView")
            val call = prepareFlow.instructions.single {
                it.operation == Operation.CALL && it.destination == Immediate(center.address - prepare.address)
            }
            // prepareRender has hidden output in RDI and the original GameView in RSI. Its selected
            // centerOn call receives the full cached SurfaceView copy in RCX; no adapter invokes either entry.
            val cached = LocalObjectCopy.analyze(prepareFlow, call.offset, 6, 1, viewSize, outputEnd.toInt())
            val surface = cached + surfaceIndex(image, outputEnd)
            require(surface >= cached && surface <= cached + outputEnd - 4 &&
                    position >= cached && position <= cached + outputEnd - 8 &&
                    (surface + 4 <= position || position + 8 <= surface)) {
                "Viewport fields do not belong to the copied cached SurfaceView"
            }
            verifyPackedBoundary(mapFlow, position)
            val instances = image.inlines.find(map, "getMapPosition", setOf("FixedPointNumberTemplate"))
            val constants = instances.flatMap { instance ->
                mapFlow.instructions.filter { instruction ->
                    instruction.operation == Operation.DOUBLE_MULTIPLY && instruction.source is Memory &&
                            instance.ranges.any { instruction.offset + map.address >= it.start &&
                                instruction.offset + map.address + instruction.size <= it.end }
                }.map { instruction ->
                    val memory = instruction.source as Memory
                    require(memory.width == 8 && memory.relative && memory.base == null && memory.index == null)
                    val next = map.address + instruction.offset + instruction.size
                    require(memory.displacement >= -next && memory.displacement <= Long.MAX_VALUE - next)
                    val address = next + memory.displacement
                    require(image.sections.any { it.flags and 7L == 2L && address >= it.address &&
                            it.size >= 8 && address - it.address <= it.size - 8 }) {
                        "Fixed-point scale is not immutable allocated ELF data"
                    }
                    val value = Double.fromBits(image.virtualBytes(address, 8).unsigned(0, 8))
                    require(value.isFinite() && value > 0)
                    value
                }
            }.distinct()
            val scale = constants.singleOrNull() ?: error("Inline fixed-point conversions disagree on their scale")
            val fraction = (0..30).singleOrNull { scale == (1L shl it).toDouble() }
                ?: error("Map coordinate scaling is not a supported binary fixed-point value")
            return ViewportCoordinates(surface, position, fraction, map)
        }

        private fun surfaceIndex(image: ElfImage, resultSize: Long): Long {
            val function = image.symbol("_ZN16RenderParameters8centerOnERK3MapRK11SurfaceView")
            val flow = X64ControlFlow.resolve(image, function)
            val arguments = SysVArgumentFlow(flow)
            val ranges = image.inlines.find(function, "centerOn", setOf("getSurfaceSafe")).flatMap { it.ranges }
            val indexed = flow.instructions.single { instruction ->
                val memory = instruction.source as? Memory
                instruction.operation == Operation.MOV && instruction.destination == Register(6, 8) &&
                        memory != null && memory.width == 8 && !memory.relative && memory.scale == 8 &&
                        memory.base != null && memory.index != null && memory.displacement == 0L &&
                        arguments.register(instruction.offset, memory.base) == SysVArgumentFlow.Reference(6)
            }
            val index = (indexed.source as Memory).index!!
            val load = flow.instructions.last { instruction ->
                instruction.offset < indexed.offset && instruction.operation == Operation.MOV &&
                        instruction.destination == Register(index, 4) && instruction.source is Memory
            }
            val member = checkNotNull(arguments.source(load.offset))
            require(member.width == 4 && member.reference.argument == 1 && member.reference.offset in 0..resultSize - 4)
            require(flow.instructions.filter { it.offset > load.offset && it.offset < indexed.offset }.none {
                it.operation == Operation.CALL || (it.destination as? Register)?.number == index
            }) { "Surface selector changes its index before the bounded lookup" }
            val guard = flow.instructions.single { instruction ->
                instruction.offset > load.offset && instruction.offset < indexed.offset &&
                        instruction.operation == Operation.CMP && instruction.source == Register(index, 8) &&
                        ranges.any { instruction.offset + function.address >= it.start &&
                            instruction.offset + function.address + instruction.size <= it.end }
            }
            val length = (guard.destination as? Register)?.takeIf { it.width == 8 } ?: error("Surface lookup has no vector length")
            val shift = flow.instructions.last { it.offset < guard.offset && it.destination == length }
            require(shift.operation == Operation.SAR && shift.source == Immediate(3))
            val subtract = flow.instructions.last { it.offset < shift.offset && it.destination == length }
            require(subtract.operation == Operation.SUB && subtract.source == Register(6, 8) &&
                    arguments.register(subtract.offset, length.number) == SysVArgumentFlow.Reference(2) &&
                    arguments.register(subtract.offset, 6) == SysVArgumentFlow.Reference(6))
            val branch = flow.body.getValue(guard.offset + guard.size)
            require(branch.operation == Operation.JCC && branch.condition == 6 &&
                    (branch.destination as? Immediate)?.value?.let { it > indexed.offset } == true &&
                    flow.predecessors[indexed.offset] == setOf(branch.offset))
            return member.reference.offset
        }

        fun verifyPackedBoundary(original: X64ControlFlow, position: Long) {
            val returns = original.instructions.filter { it.operation == Operation.RET && it.offset in original.reachable }
            require(returns.size == 1)
            val terminal = returns.single()
            val flow = original.reaching(terminal.offset)
            val instructions = flow.instructions.filter { it.offset in flow.reachable }
            val incoming = flow.reachable.associateWith { flow.predecessors[it].orEmpty().size }.toMutableMap()
            val ready = ArrayDeque<Long>()
            ready.addAll(incoming.filterValues { it == 0 }.keys)
            var ordered = 0
            while (ready.isNotEmpty()) {
                val site = ready.removeFirst()
                ++ordered
                for (next in flow.successors.getValue(site)) {
                    incoming[next] = incoming.getValue(next) - 1
                    if (incoming[next] == 0) ready.add(next)
                }
            }
            require(ordered == flow.reachable.size) { "Packed coordinate getter has an unsupported loop" }
            fun dominates(from: Long, to: Long) {
                val pending = ArrayDeque<Long>()
                val visited = mutableSetOf<Long>()
                pending.add(0)
                while (pending.isNotEmpty()) {
                    val site = pending.removeFirst()
                    if (site == from || !visited.add(site)) continue
                    require(site != to) { "Packed coordinate evidence can be bypassed" }
                    pending.addAll(flow.successors.getValue(site))
                }
            }
            val arguments = SysVArgumentFlow(flow)
            val frame = SysVLocalArgument(flow)
            for (instruction in instructions) {
                val memory = instruction.destination as? Memory ?: continue
                if (instruction.operation !in setOf(Operation.CMP, Operation.TEST, Operation.BIT_TEST,
                        Operation.SCALAR_COMPARE, Operation.CALL, Operation.JMP))
                    require(frame.address(instruction.offset, memory) != null) {
                        "Map position getter writes outside its local frame"
                    }
            }
            require(instructions.any { instruction ->
                val source = arguments.source(instruction.offset)
                instruction.operation == Operation.MOV && source?.reference == SysVArgumentFlow.Reference(7, position) && source.width == 8
            }) { "Map position getter does not read its original receiver's cached position" }
            val high = instructions.singleOrNull { instruction ->
                val target = instruction.destination as? Register
                instruction.operation == Operation.SHR && instruction.source == Immediate(32) && target?.width == 8 &&
                        arguments.register(instruction.offset, target.number) == SysVArgumentFlow.Reference(6)
            } ?: error("PixelPosition's second component is not the high word of RSI")
            val low = instructions.singleOrNull { instruction ->
                val target = instruction.destination as? Register
                instruction.operation == Operation.SUB && target?.width == 4 &&
                        arguments.register(instruction.offset, target.number) == SysVArgumentFlow.Reference(6)
            } ?: error("PixelPosition's first component is not the low word of RSI")
            fun conversion(after: Long, register: Int, highWord: Boolean): Instruction {
                val conversion = instructions.singleOrNull { it.offset > after &&
                    it.operation == Operation.INT_TO_DOUBLE && it.source == Register(register, 4) }
                    ?: error("Pixel component is not converted from a signed integer")
                dominates(after, conversion.offset)
                val between = instructions.filter { it.offset > after && it.offset < conversion.offset }
                require(register !in listOf(0, 1, 2, 6, 7, 8, 9, 10, 11) || between.none { it.operation == Operation.CALL })
                val writes = between.filter { (it.destination as? Register)?.number == register &&
                        it.operation !in setOf(Operation.CMP, Operation.TEST, Operation.BIT_TEST) }
                require(writes.isEmpty() || highWord && writes.size == 1 &&
                        writes.single().operation == Operation.SUB && writes.single().destination == Register(register, 4)) {
                    "Pixel component is overwritten before conversion"
                }
                return conversion
            }
            require(conversion(low.offset, (low.destination as Register).number, false).destination == Register(16, 8) &&
                    conversion(high.offset, (high.destination as Register).number, true).destination == Register(17, 8))
            val combine = instructions.last { it.offset < terminal.offset &&
                    it.operation == Operation.OR && it.destination == Register(0, 8) }
            dominates(combine.offset, terminal.offset)
            val upper = combine.source as? Register ?: error("Packed map result has no high register")
            require(upper.width == 8)
            val shift = instructions.last { it.offset < combine.offset &&
                it.operation == Operation.SHL && it.destination == upper && it.source == Immediate(32) }
            val x = instructions.single { it.offset < combine.offset && it.operation == Operation.TRUNCATE_DOUBLE &&
                    it.destination == Register(0, 4) && it.source == Register(16, 8) }
            val y = instructions.single { it.offset < shift.offset && it.operation == Operation.TRUNCATE_DOUBLE &&
                    it.destination == Register(upper.number, 4) && it.source == Register(17, 8) }
            dominates(x.offset, combine.offset)
            dominates(y.offset, shift.offset)
            dominates(shift.offset, combine.offset)
            require(instructions.filter { it.offset > combine.offset && it.offset < terminal.offset }.all {
                it.operation == Operation.POP && it.destination is Register && it.destination.number in listOf(3, 5, 12, 13, 14, 15) ||
                        it.operation == Operation.ADD && it.destination == Register(4, 8)
            }) { "Packed map result is changed before returning" }
        }
    }
}
