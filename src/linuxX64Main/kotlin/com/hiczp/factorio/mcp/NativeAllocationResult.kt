package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Exact allocator-result provenance, independently of the meaning of the fields written through it. */
internal object NativeAllocationResult {
    fun find(flow: X64ControlFlow, allocator: Long, size: Long): Instruction =
        flow.instructions.zipWithNext().filter { (amount, call) ->
            amount.operation == Operation.MOV && amount.destination == Register(
                7,
                4
            ) && amount.source == Immediate(size) &&
                    call.operation == Operation.CALL && call.destination == Immediate(allocator) &&
                    call.offset in flow.reachable && flow.predecessors[call.offset] == setOf(amount.offset)
        }.map { it.second }.singleOrNull() ?: error("Object has no unique bounded allocation")

    fun verify(flow: X64ControlFlow, allocation: Instruction, store: Instruction, base: Int) {
        val start = allocation.offset + allocation.size
        val region = flow.instructions.filter { it.offset in start..store.offset }
        require(region.isNotEmpty() && region.first().offset == start && region.last() == store)
        val sites = region.map { it.offset }.toSet()
        require(region.all { instruction ->
            flow.predecessors[instruction.offset].orEmpty().all {
                it in sites || instruction.offset == start && it == allocation.offset
            }
        }) { "Object initialization has an entry bypassing allocation" }
        val local = X64ControlFlow(region.map { instruction ->
            val target = instruction.destination as? Immediate
            instruction.copy(
                offset = instruction.offset - start, destination =
                    if (target != null && instruction.operation in listOf(Operation.CALL, Operation.JMP, Operation.JCC))
                        Immediate(target.value - start) else instruction.destination
            )
        })
        val allocated = SysVArgumentFlow(local, entryReferences = mapOf(0 to SysVArgumentFlow.Reference(7)))
        require(allocated.register(store.offset - start, base) == SysVArgumentFlow.Reference(7)) {
            "Object store does not use this allocation's original result"
        }
    }
}
