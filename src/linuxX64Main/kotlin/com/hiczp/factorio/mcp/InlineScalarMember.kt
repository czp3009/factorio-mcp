package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Receiver-relative scalar load in the selected named inline ranges. Other receivers supply no candidates. */
internal object InlineScalarMember {
    fun analyze(flow: X64ControlFlow, ranges: List<DwarfRanges.Range>, objectSize: Long, width: Int): Long {
        require(objectSize in 8..64 * 1024 * 1024 && width in listOf(1, 2, 4, 8) && ranges.isNotEmpty())
        val boundaries = flow.body.keys + flow.instructions.last().let { it.offset + it.size }
        require(ranges.all { it.start in boundaries && it.end in boundaries && it.end > it.start })
        val arguments = SysVArgumentFlow(flow)
        val candidates = flow.instructions.mapNotNull { instruction ->
            if (instruction.offset !in flow.reachable || instruction.operation !in setOf(Operation.MOV, Operation.MOVZX) ||
                ranges.none { instruction.offset >= it.start && instruction.offset + instruction.size <= it.end }
            ) return@mapNotNull null
            val target = instruction.destination as? Register ?: return@mapNotNull null
            val source = arguments.source(instruction.offset) ?: return@mapNotNull null
            if (source.reference.argument != 7 || source.width != width || target.width < width) return@mapNotNull null
            source.reference.offset.also { require(it in 8..objectSize - width) }
        }.distinct()
        return candidates.singleOrNull() ?: error("Named inline getter has no unique bounded receiver scalar")
    }
}
