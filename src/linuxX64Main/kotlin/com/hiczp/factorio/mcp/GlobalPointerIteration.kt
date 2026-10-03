package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Named global vector bounds witnessed by an empty guard, pointer dereference and guarded advancing loop. */
internal object GlobalPointerIteration {
    fun analyze(flow: X64ControlFlow, address: Long, global: Long, size: Long, first: Long): Long {
        require(address >= 0 && global > 0 && size in 16..4096 && first in 0..size - 8 && first % 8 == 0L)
        val loads = flow.instructions.mapNotNull { instruction ->
            val source = instruction.source as? Memory ?: return@mapNotNull null
            if (instruction.offset !in flow.reachable || instruction.operation != Operation.MOV ||
                instruction.destination !is Register || instruction.destination.width != 8 || source.width != 8 ||
                !source.relative || source.base != null || source.index != null) return@mapNotNull null
            val member = address + instruction.offset + instruction.size + source.displacement - global
            if (member !in 0..size - 8 || member % 8 != 0L) return@mapNotNull null
            instruction.offset to PrivateValueCopies.Read(instruction.offset, InlineArgumentFields.Field(member, 8))
        }.toMap()
        require(loads.size in 2..16)
        return PointerIteration.analyze(flow, first, loads)
    }
}
