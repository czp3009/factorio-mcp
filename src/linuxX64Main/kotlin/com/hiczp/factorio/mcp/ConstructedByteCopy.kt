package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Copies an independently identified original receiver byte into a bounded constructed allocation. */
internal object ConstructedByteCopy {
    fun analyze(
        bytes: BinaryView, address: Long, allocator: Long, constructor: Long, size: Long,
        sourceSize: Long, sourceField: Long
    ): Long {
        require(sourceSize in 8..4096 && sourceField in 8 until sourceSize)
        val flow = X64ControlFlow(X64Instructions(bytes).all(4096))
        val arguments = SysVArgumentFlow(flow)
        val ranges = flow.instructions.zipWithNext().mapNotNull { (load, store) ->
            val memory = load.source as? Memory ?: return@mapNotNull null
            val register = load.destination as? Register ?: return@mapNotNull null
            if (load.offset !in flow.reachable || load.operation != Operation.MOVZX || memory.width != 1 ||
                register.width != 4 || register.number !in 0..15 ||
                arguments.memory(load.offset, memory)?.reference != SysVArgumentFlow.Reference(7, sourceField)
            )
                return@mapNotNull null
            require(
                store.operation == Operation.MOV && store.source == Register(register.number, 1) &&
                        store.destination is Memory && flow.predecessors[store.offset] == setOf(load.offset)
            ) {
                "Identified byte is not copied directly to the constructed object"
            }
            DwarfRanges.Range(store.offset, store.offset + store.size)
        }
        require(ranges.size == 1) { "Identified byte has no unique constructed copy" }
        return ConstructedInlineByteMember.analyze(bytes, address, allocator, constructor, size, ranges)
    }
}
