package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Named byte store on the same sized allocation passed to a selected constructor. Never calls the constructor. */
internal object ConstructedInlineByteMember {
    fun analyze(
        bytes: BinaryView, address: Long, allocator: Long, constructor: Long, size: Long,
        ranges: List<DwarfRanges.Range>
    ): Long {
        require(
            bytes.size in 1..4096 && size in 16..16 * 1024 * 1024 && address > 0 &&
                    address <= Long.MAX_VALUE - bytes.size && allocator > 0 && constructor > 0
        )
        val flow = X64ControlFlow(X64Instructions(bytes).all(4096))
        val allocation = NativeAllocationResult.find(flow, allocator - address, size)
        val boundaries = flow.body.keys + bytes.size
        require(ranges.isNotEmpty() && ranges.all {
            it.start >= 0 && it.end > it.start &&
                    it.start in boundaries && it.end in boundaries
        })
        val body = flow.instructions.filter { instruction ->
            ranges.any {
                instruction.offset >= it.start && instruction.offset + instruction.size <= it.end
            }
        }
        val store = body.singleOrNull() ?: error("Named byte setter must contain exactly one store")
        val memory = store.destination as? Memory ?: error("Named byte setter has no member destination")
        val source = store.source as? Register ?: error("Named byte setter has no scalar source")
        require(
            store.offset in flow.reachable && store.operation == Operation.MOV && source.number in 0..15 &&
                    source.width == 1 && memory.width == 1 && !memory.relative && memory.index == null &&
                    memory.base != null && memory.displacement in 8 until size
        )
        NativeAllocationResult.verify(flow, allocation, store, memory.base)
        val construction = flow.instructions.filter {
            it.operation == Operation.CALL &&
                    it.destination == Immediate(constructor - address) && it.offset in flow.reachable
        }.singleOrNull() ?: error("Allocation has no unique selected constructor call")
        require(construction.offset > allocation.offset && construction.offset < store.offset)
        NativeAllocationResult.verify(flow, allocation, construction, 7)
        // Every path reaching the setter must first pass through construction.
        val pending = ArrayDeque<Long>()
        val visited = mutableSetOf<Long>()
        pending.add(store.offset)
        while (pending.isNotEmpty()) {
            val site = pending.removeFirst()
            if (site == construction.offset || !visited.add(site)) continue
            require(site > construction.offset) { "Named setter can bypass object construction" }
            val predecessors = flow.predecessors[site].orEmpty()
            require(predecessors.isNotEmpty())
            pending.addAll(predecessors)
        }
        return memory.displacement
    }
}
