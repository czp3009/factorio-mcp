package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Required-modifier bits associated with named native checks. Other bits retain their raw meaning. */
internal data class ControlModifiers(val field: Long, val control: Int, val shift: Int, val alt: Int) {
    companion object {
        fun resolve(image: ElfImage, extent: Long, debug: DwarfInlines = DwarfInlines(image)): ControlModifiers =
            resolve(
                image, extent,
                "_ZN17ControlInputValue27checkRequiredModifiersMatchEPKS_", "checkRequiredModifiersMatch",
                "_ZNK10InputState10isCtrlDownEv", "_ZNK10InputState11isShiftDownEv", "isAltDown", debug
            )

        fun resolve(
            image: ElfImage, extent: Long, function: String, ownerName: String,
            control: String, shift: String, alt: String, debug: DwarfInlines = DwarfInlines(image)
        ): ControlModifiers {
            val entry = image.symbol(function)
            EhFrames(image).function(entry)
            val flow = X64ControlFlow.resolve(image, entry)
            fun calls(name: String): Set<Long> {
                val callee = image.symbol(name)
                EhFrames(image).function(callee)
                return flow.instructions.filter {
                    it.operation == Operation.CALL &&
                            it.destination == Immediate(callee.address - entry.address)
                }.map { it.offset }.toSet().also {
                    require(it.isNotEmpty())
                }
            }

            val inlines = debug.find(entry, ownerName, setOf(alt))
            require(inlines.map { it.origin }.distinct().size == 1)
            val altStarts = inlines.flatMap { it.ranges }.map { it.start - entry.address }.toSet()
            return analyze(flow, extent, listOf(calls(control), calls(shift), altStarts))
        }

        fun analyze(flow: X64ControlFlow, extent: Long, checks: List<Set<Long>>): ControlModifiers {
            require(extent in 1..4096 && checks.size == 3 && checks.all { it.isNotEmpty() && it.all { site -> site in flow.reachable } })
            require(checks.flatten().distinct().size == checks.sumOf { it.size })
            val arguments = SysVArgumentFlow(flow)
            val reads = flow.instructions.mapNotNull { instruction ->
                if (instruction.operation !in setOf(Operation.MOV, Operation.MOVZX)) return@mapNotNull null
                val read = arguments.source(instruction.offset) ?: return@mapNotNull null
                if (read.reference.argument != 7 || read.width != 1) return@mapNotNull null
                require(read.reference.offset in 0 until extent)
                instruction.offset to PrivateValueCopies.Read(0, InlineArgumentFields.Field(read.reference.offset, 1))
            }.toMap()
            val copies = if (reads.isEmpty()) null else PrivateValueCopies(flow, reads)
            fun straightTarget(start: Long): Int? {
                var site = start
                repeat(16) {
                    val target = checks.indices.singleOrNull { site in checks[it] }
                    if (target != null) return target
                    val instruction = flow.body[site] ?: return null
                    if (instruction.operation !in setOf(
                            Operation.MOV,
                            Operation.MOVZX,
                            Operation.LEA,
                            Operation.NOP
                        )
                    ) return null
                    // A named check may follow pointer loads/argument copies, never an intervening write.
                    if (instruction.destination is Memory) return null
                    site += instruction.size
                }
                return null
            }

            val found = List(3) { mutableSetOf<Pair<Long, Int>>() }
            for (branch in flow.instructions.filter { it.operation == Operation.JCC && it.condition in setOf(4, 5) }) {
                val test = flow.instructions.singleOrNull { it.offset + it.size == branch.offset } ?: continue
                if (test.operation != Operation.TEST) continue
                val mask = (test.source as? Immediate)?.value ?: continue
                if (mask !in 1..255 || mask and (mask - 1) != 0L) continue
                val target = (branch.destination as? Immediate)?.value ?: error("Indirect modifier branch")
                val fallthrough = branch.offset + branch.size
                val enabled = if (branch.condition == 5) target else fallthrough
                val disabled = if (branch.condition == 5) fallthrough else target
                val check = straightTarget(enabled) ?: continue
                require(straightTarget(disabled) != check) { "Modifier bit does not guard its named check" }
                val field = when (val input = test.destination) {
                    is Register -> {
                        require(input.width == 1)
                        checkNotNull(copies).field(test.offset, input)
                            .also { require(it.source == 0L && it.field.width == 1) }.field.offset
                    }

                    is Memory -> {
                        val read = arguments.memory(test.offset, input) ?: error("Modifier guard has no original value")
                        require(read.reference.argument == 7 && read.width == 1)
                        read.reference.offset
                    }

                    else -> error("Modifier bit guard has an unsupported input")
                }
                require(field in 0 until extent)
                found[check] += field to mask.toInt()
            }
            val flags = found.map { it.singleOrNull() ?: error("Required modifier has no unique guarded native check") }
            require(flags.map { it.first }.distinct().size == 1 && flags.map { it.second }.distinct().size == 3)
            return ControlModifiers(flags.first().first, flags[0].second, flags[1].second, flags[2].second)
        }
    }
}
