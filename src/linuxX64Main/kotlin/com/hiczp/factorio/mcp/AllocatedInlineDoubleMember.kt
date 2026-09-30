package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Named scalar setter on the same bounded allocation that receives a verified concrete primary table. */
internal object AllocatedInlineDoubleMember {
    fun analyze(
        bytes: BinaryView, address: Long, allocator: Long, size: Long, table: Long,
        ranges: List<DwarfRanges.Range>
    ): Long {
        require(
            bytes.size in 1..4096 && size in 16..16 * 1024 * 1024 && address > 0 &&
                    address <= Long.MAX_VALUE - bytes.size && allocator > 0 && table > 0
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
        require(body.isNotEmpty() && body.all {
            it.offset in flow.reachable && it.operation in listOf(
                Operation.SCALAR_MOV, Operation.DOUBLE_MINIMUM, Operation.DOUBLE_MAXIMUM,
                Operation.VECTOR_XOR, Operation.NOP, Operation.ENDBR,
            )
        }) { "Named setter contains unsupported operations" }
        val stores = body.filter { it.destination is Memory }
        val store = stores.singleOrNull() ?: error("Named setter does not have one scalar member store")
        val memory = store.destination as Memory
        val source = store.source as? Register ?: error("Named double setter does not store an XMM value")
        require(
            store.operation == Operation.SCALAR_MOV && source.number in 16..31 && source.width == 8 &&
                    memory.width == 8 && !memory.relative && memory.index == null && memory.base != null &&
                    memory.displacement in 8..size - 8
        )
        NativeAllocationResult.verify(flow, allocation, store, memory.base)
        val definitions = ScalarExpression(flow)
        val tables = flow.instructions.filter { instruction ->
            val target = instruction.destination as? Memory
            val value = instruction.source as? Register
            if (instruction.offset !in flow.reachable || instruction.offset >= store.offset ||
                instruction.operation != Operation.MOV || target == null || target.width != 8 ||
                target.relative || target.index != null || target.base == null || target.displacement != 0L ||
                value?.width != 8
            ) return@filter false
            val definition = definitions.definition(instruction.offset, value.number)
            val pointer = definition.source as? Memory ?: return@filter false
            definition.operation == Operation.LEA && pointer.relative && pointer.base == null && pointer.index == null &&
                    address + definition.offset + definition.size + pointer.displacement == table
        }
        val tableStore = tables.singleOrNull() ?: error("Allocation lacks a unique concrete primary table store")
        NativeAllocationResult.verify(flow, allocation, tableStore, (tableStore.destination as Memory).base!!)
        return memory.displacement
    }
}
