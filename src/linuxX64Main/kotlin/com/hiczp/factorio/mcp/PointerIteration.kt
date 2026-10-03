package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Independently identified pointer-vector loads, with guarded bounds and exactly one pointer-width advance. */
internal object PointerIteration {
    fun analyze(flow: X64ControlFlow, first: Long, loads: Map<Long, PrivateValueCopies.Read>): Long {
        require(loads.size in 2..16 && loads.values.any { it.field.offset == first && it.field.width == 8 })
        val copies = PrivateValueCopies(flow, loads)
        fun member(site: Long, operand: Operand?): Long? {
            val register = operand as? Register ?: return null
            return runCatching { copies.field(site, register).field.offset }.getOrNull()
        }

        fun reaches(from: Long, to: Long, blocked: Set<Long> = emptySet()): Boolean {
            val pending = ArrayDeque<Long>()
            val visited = mutableSetOf<Long>()
            pending.add(from)
            while (pending.isNotEmpty()) {
                val next = pending.removeFirst()
                if (next in blocked || !visited.add(next)) continue
                if (next == to) return true
                pending.addAll(flow.successors.getValue(next))
            }
            return false
        }

        data class Guard(val branch: Long, val unequal: Long, val equal: Long)
        fun guard(compare: Instruction): Guard {
            require(compare.operation == Operation.CMP)
            val branch = flow.body.getValue(compare.offset + compare.size)
            require(branch.operation == Operation.JCC && branch.condition in listOf(4, 5) &&
                    flow.predecessors[branch.offset] == setOf(compare.offset))
            val target = (branch.destination as? Immediate)?.value ?: error("Indirect range guard")
            val next = branch.offset + branch.size
            require(target in flow.body && next in flow.body)
            return if (branch.condition == 4) Guard(branch.offset, next, target) else Guard(branch.offset, target, next)
        }

        val candidates = flow.instructions.filter { it.offset in flow.reachable && it.operation == Operation.CMP }
            .mapNotNull { comparison -> runCatching {
                val operands = listOf(comparison.destination, comparison.source)
                val cursor = operands.single { member(comparison.offset, it) == first } as? Register
                    ?: error("Initial cursor is not a pointer register")
                require(cursor.width == 8 && cursor.number in listOf(3, 5, 12, 13, 14, 15))
                val end = checkNotNull(member(comparison.offset, operands.single { it != cursor }))
                require(end != first)
                val empty = guard(comparison)
                val reads = flow.instructions.filter { it.offset in flow.reachable && it.operation == Operation.MOV &&
                        it.destination is Register && it.destination.width == 8 && (it.source as? Memory)?.let { source ->
                    source.base == cursor.number && source.index == null && !source.relative &&
                            source.displacement == 0L && source.width == 8
                } == true }
                val dereference = reads.single()
                require(reaches(empty.unequal, dereference.offset) && !reaches(empty.equal, dereference.offset) &&
                        !reaches(0, dereference.offset, setOf(empty.branch)))
                val relevant = flow.reaching(dereference.offset).reachable
                val updates = flow.instructions.filter { it.offset in relevant && it.destination is Register &&
                        it.destination.number == cursor.number && it.operation !in listOf(Operation.CMP, Operation.TEST,
                    Operation.PUSH, Operation.NOP, Operation.JMP, Operation.JCC) }
                val initialization = updates.single { loads[it.offset]?.field?.offset == first }
                val advance = updates.single { it.offset != initialization.offset }
                require(advance.operation == Operation.ADD && advance.destination == cursor && advance.source == Immediate(8))
                require(!reaches(dereference.offset, initialization.offset))
                // Every repeat of the element read crosses its sole pointer-width increment.
                require(flow.successors.getValue(dereference.offset).none {
                    reaches(it, dereference.offset, setOf(advance.offset))
                })
                val loopGuards = flow.instructions.filter { it.offset in relevant && it.operation == Operation.CMP &&
                        cursor in listOf(it.destination, it.source) && it.offset != comparison.offset }
                    .filter { compare ->
                        val other = listOf(compare.destination, compare.source).single { it != cursor }
                        member(compare.offset, other) == end
                    }.map { compare -> compare to guard(compare) }.filter { (compare, loop) ->
                        reaches(advance.offset, compare.offset) && !reaches(empty.unequal, compare.offset, setOf(advance.offset)) &&
                                reaches(loop.unequal, dereference.offset) && !reaches(loop.equal, dereference.offset) &&
                                !reaches(advance.offset, dereference.offset, setOf(loop.branch))
                    }
                require(loopGuards.size == 1)
                end
            }.getOrNull() }.distinct()
        return candidates.singleOrNull() ?: error("Named pointer vector has no unique validated iteration end")
    }
}
