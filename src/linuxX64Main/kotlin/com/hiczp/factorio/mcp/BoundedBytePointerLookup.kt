package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** A zero-extended receiver byte indexing a constant pointer table behind an unsigned, typed error guard. */
internal data class BoundedBytePointerLookup(
    val load: Long,
    val source: Long,
    val field: Long,
    val table: Long,
    val count: Int,
) {
    companion object {
        fun analyze(flow: X64ControlFlow, address: Long, load: Long, errorTarget: Long): BoundedBytePointerLookup {
            require(address >= 0 && address <= Long.MAX_VALUE - 32768 && load in flow.reachable)
            val instruction = flow.body.getValue(load)
            val memory = instruction.source as? Memory ?: error("Lookup does not read a pointer")
            require(instruction.operation == Operation.MOV && instruction.destination is Register &&
                    instruction.destination.width == 8 && memory.width == 8 && !memory.relative &&
                    memory.scale == 8 && memory.index != null && memory.base != null)
            val definitions = ScalarExpression(flow)
            val arguments = SysVArgumentFlow(flow)
            fun byte(site: Long, register: Int, depth: Int = 0): Pair<Long, Long> {
                require(depth < 32)
                val definition = definitions.definition(site, register)
                val target = definition.destination as? Register ?: error("Lookup index has no register definition")
                require(target.number == register && target.width in listOf(4, 8))
                return when (val input = definition.source) {
                    is Register -> {
                        require(definition.operation == Operation.MOV && input.width in listOf(4, 8))
                        byte(definition.offset, input.number, depth + 1)
                    }
                    is Memory -> {
                        require(definition.operation == Operation.MOVZX && input.width == 1)
                        val read = arguments.memory(definition.offset, input)
                            ?: error("Lookup byte is not an original receiver field")
                        require(read.reference.argument == 7 && read.reference.offset in 0..63)
                        definition.offset to read.reference.offset
                    }
                    else -> error("Lookup index is changed or is not a byte")
                }
            }
            fun literal(site: Long, register: Int, depth: Int = 0): Long {
                require(depth < 32)
                val definition = definitions.definition(site, register)
                require(definition.destination == Register(register, 8))
                return when (definition.operation) {
                    Operation.MOV -> {
                        val input = definition.source as? Register ?: error("Table base is not a preserved address")
                        require(input.width == 8)
                        literal(definition.offset, input.number, depth + 1)
                    }
                    Operation.LEA -> {
                        val input = definition.source as? Memory ?: error("Table base has no address")
                        require(input.relative && input.base == null && input.index == null)
                        val next = address + definition.offset + definition.size
                        require(input.displacement >= -next && input.displacement <= Long.MAX_VALUE - next)
                        next + input.displacement
                    }
                    else -> error("Table base is not a constant image-relative address")
                }
            }
            val source = byte(load, memory.index)
            val base = literal(load, memory.base)
            require(memory.displacement >= -base && memory.displacement <= Long.MAX_VALUE - base)
            val table = base + memory.displacement
            require(table > 0 && table % 8 == 0L)
            val guards = flow.instructions.filter { it.offset in flow.reachable && it.operation == Operation.JCC &&
                    it.condition in listOf(2, 3) }.mapNotNull { branch ->
                val comparison = flags(flow, branch.offset) ?: return@mapNotNull null
                val index = comparison.destination as? Register ?: return@mapNotNull null
                val maximum = (comparison.source as? Immediate)?.value ?: return@mapNotNull null
                if (comparison.operation != Operation.CMP || index.width !in listOf(4, 8) || maximum !in 1..256)
                    return@mapNotNull null
                if (runCatching { byte(comparison.offset, index.number) }.getOrNull() != source) return@mapNotNull null
                val taken = (branch.destination as? Immediate)?.value ?: return@mapNotNull null
                val next = branch.offset + branch.size
                val accepted = if (branch.condition == 2) taken else next
                val rejected = if (branch.condition == 2) next else taken
                if (!dominates(flow, branch.offset, accepted, load) || !fails(flow, rejected, errorTarget)) return@mapNotNull null
                maximum.toInt()
            }.distinct()
            return BoundedBytePointerLookup(load, source.first, source.second, table,
                guards.singleOrNull() ?: error("Pointer lookup lacks one dominating unsigned byte bound"))
        }

        private fun flags(flow: X64ControlFlow, site: Long): Instruction? {
            val pending = ArrayDeque<Long>()
            pending.addAll(flow.predecessors[site].orEmpty())
            val visited = mutableSetOf<Long>()
            val found = mutableSetOf<Long>()
            while (pending.isNotEmpty()) {
                val current = pending.removeFirst()
                if (!visited.add(current)) continue
                require(visited.size <= 4096)
                val instruction = flow.body.getValue(current)
                if (instruction.operation in listOf(Operation.MOV, Operation.MOVZX, Operation.MOVSX,
                        Operation.LEA, Operation.NOP, Operation.ENDBR, Operation.PUSH, Operation.POP,
                        Operation.JMP, Operation.JCC, Operation.CMOV, Operation.SET,
                        Operation.SCALAR_MOV, Operation.VECTOR_MOV)) {
                    if (current == 0L) return null
                    pending.addAll(flow.predecessors[current].orEmpty())
                } else found += current
            }
            return found.singleOrNull()?.let(flow.body::getValue)
        }

        private fun dominates(flow: X64ControlFlow, branch: Long, accepted: Long, target: Long): Boolean {
            val pending = ArrayDeque<Long>()
            val visited = mutableSetOf<Long>()
            pending.add(0)
            while (pending.isNotEmpty()) {
                val current = pending.removeFirst()
                if (current == target) return false
                if (visited.add(current)) pending.addAll(flow.successors.getValue(current).filter {
                    current != branch || it != accepted
                })
            }
            return true
        }

        private fun fails(flow: X64ControlFlow, start: Long, errorTarget: Long): Boolean {
            if (start !in flow.reachable) return false
            val pending = ArrayDeque<Long>()
            val visited = mutableSetOf<Long>()
            pending.add(start)
            var terminal = false
            while (pending.isNotEmpty()) {
                val current = pending.removeFirst()
                if (!visited.add(current)) continue
                require(visited.size <= 4096)
                val instruction = flow.body.getValue(current)
                if (instruction.operation == Operation.CALL && instruction.destination == Immediate(errorTarget)) {
                    terminal = true
                    continue
                }
                if (instruction.operation in listOf(Operation.CALL, Operation.RET) ||
                    flow.successors.getValue(current).isEmpty()) return false
                pending.addAll(flow.successors.getValue(current))
            }
            return terminal
        }
    }
}
