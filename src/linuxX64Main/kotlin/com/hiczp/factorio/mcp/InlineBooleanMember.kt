package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Own boolean gate inside a named inline expression, without evaluating subsequent style or pointer reads. */
internal object InlineBooleanMember {
    fun analyze(bytes: BinaryView, ranges: List<DwarfRanges.Range>, size: Long): Long {
        require(bytes.size in 1..4096 && size in 1..16 * 1024 * 1024)
        val flow = X64ControlFlow(X64Instructions(bytes).all(4096))
        val boundaries = flow.body.keys + bytes.size
        require(ranges.isNotEmpty() && ranges.all {
            it.start >= 0 && it.end > it.start &&
                    it.start in boundaries && it.end in boundaries
        })
        val arguments = SysVArgumentFlow(flow)
        val gates = flow.instructions.filter { instruction ->
            instruction.offset in flow.reachable && ranges.any {
                instruction.offset >= it.start &&
                        instruction.offset + instruction.size <= it.end
            } && instruction.operation == Operation.CMP &&
                    instruction.source == Immediate(1) && (instruction.destination as? Memory)?.width == 1
        }.mapNotNull { comparison ->
            val memory = comparison.destination as Memory
            val field = arguments.memory(comparison.offset, memory) ?: return@mapNotNull null
            if (field.reference.argument != 7) return@mapNotNull null
            require(field.width == 1)
            var next = flow.body.getValue(comparison.offset + comparison.size)
            // The compiler may materialize a zero result without changing the comparison flags.
            if (next.operation == Operation.MOV) {
                require(next.destination is Register && next.destination.width == 4 && next.source == Immediate(0))
                next = flow.body.getValue(next.offset + next.size)
            }
            require(
                next.operation == Operation.JCC && next.condition == 5 &&
                        next.destination is Immediate && next.destination.value > next.offset + next.size
            ) {
                "Own boolean does not guard the inline expression with a forward false branch"
            }
            val between = flow.instructions.filter { it.offset > comparison.offset && it.offset <= next.offset }
            require(between.zip(listOf(comparison) + between.dropLast(1)).all { (instruction, previous) ->
                flow.predecessors[instruction.offset] == setOf(previous.offset)
            }) { "Boolean branch has an entry bypassing its comparison" }
            NativeAccessor(field.reference.offset, 1, 0xffUL, 0).withinObject(size).offset
        }.distinct()
        return gates.singleOrNull() ?: error("Named inline expression has no unique own boolean gate")
    }
}
