package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Proves that the selected mouse post-update paths only inspect type and restore their own frame. */
internal object MousePostUpdate {
    fun resolve(image: ElfImage, conversion: SdlButtonConversion): Map<Long, List<Long>> {
        val function = image.symbol("_ZN10InputState10postUpdateERK5Event")
        return analyze(X64ControlFlow.resolve(image, function), conversion.header, conversion.kinds.values.toSet())
    }

    fun analyze(flow: X64ControlFlow, header: EventHeader, kinds: Set<Long>): Map<Long, List<Long>> {
        require(kinds.size == 2 && kinds.all { it in 0..0xffffffffL } && header.extent in 8..4096)
        val arguments = SysVArgumentFlow(flow)
        val type = SysVArgumentFlow.Read(SysVArgumentFlow.Reference(6, header.type), 4)
        val paths = ScalarBranchPath(flow, mapOf(6 to header.extent.toLong()))
        val returns = flow.instructions.filter { it.offset in flow.reachable && it.operation == Operation.RET }
        require(returns.size in 1..16)
        return kinds.associateWith { kind ->
            val candidates = returns.mapNotNull { returned ->
                try {
                    val pops = mutableListOf<Register>()
                    var start = returned.offset
                    repeat(8) {
                        val previous = flow.predecessors[start]?.singleOrNull()?.let { flow.body.getValue(it) }
                        if (previous?.operation == Operation.POP && previous.offset + previous.size == start) {
                            val register = previous.destination as? Register ?: error("Non-register frame restoration")
                            require(register.width == 8 && register.number in listOf(3, 5, 12, 13, 14, 15))
                            pops += register
                            start = previous.offset
                        }
                    }
                    val path = paths.to(start) {
                        require(it.field == type)
                        kind
                    }
                    val pushes = mutableListOf<Register>()
                    for (site in path.dropLast(1)) {
                        val instruction = flow.body.getValue(site)
                        when (instruction.operation) {
                            Operation.PUSH -> {
                                val register = instruction.destination as? Register ?: error("Non-register saved frame")
                                require(register.width == 8 && register.number in listOf(3, 5, 12, 13, 14, 15))
                                require(register !in pushes)
                                pushes += register
                            }

                            Operation.MOV -> {
                                val target = instruction.destination as? Register ?: error("Post-update writes memory")
                                if (target == Register(5, 8) && instruction.source == Register(4, 8)) {
                                    require(Register(5, 8) in pushes)
                                } else {
                                    require(
                                        target.number in listOf(
                                            0,
                                            1,
                                            2,
                                            6,
                                            7,
                                            8,
                                            9,
                                            10,
                                            11
                                        ) && target.width in listOf(1, 2, 4, 8)
                                    )
                                    val source = instruction.source
                                    require(
                                        source is Register || source is Immediate ||
                                                source is Memory && arguments.source(site) == type
                                    )
                                }
                            }

                            Operation.CMP, Operation.TEST -> {
                                for (operand in listOf(
                                    instruction.destination,
                                    instruction.source
                                )) if (operand is Memory)
                                    require(arguments.memory(site, operand) == type)
                            }

                            Operation.JCC, Operation.JMP, Operation.NOP, Operation.ENDBR -> Unit
                            else -> error("Mouse post-update has an observable or unsupported effect")
                        }
                    }
                    require(pushes == pops) { "Mouse post-update does not restore its preserved registers" }
                    path + flow.instructions.filter { it.offset > start && it.offset <= returned.offset }
                        .map { it.offset }
                } catch (_: IllegalArgumentException) {
                    null
                } catch (_: IllegalStateException) {
                    null
                }
            }
            candidates.singleOrNull() ?: error("Mouse post-update has no unique verified no-op return path")
        }
    }
}
